package com.flydb.core.executor;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.log.Log;
import com.flydb.core.test.JdbcFakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SqlMigrationExecutor 单测（设计 04 §1.4）。
 *
 * <p>覆盖：占位符替换在词法解析之前、逐条执行、失败异常携带脚本名/语句序号/起始行号/驱动原始错误。
 * JDBC 连接用 {@link JdbcFakes}（JDK 动态代理，零依赖）。
 */
@DisplayName("SqlMigrationExecutor")
class SqlMigrationExecutorTest {

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static MigrationExecutor executor(String script, String sql) {
        return new SqlMigrationExecutor(script, sql,
                SqlStatementBuilderConfig.postgresql(), "${", "}",
                map(), map());
    }

    private static SqlMigrationExecutor batchExecutor(String script, String sql, int batchSize) {
        return new SqlMigrationExecutor(script, sql,
                SqlStatementBuilderConfig.postgresql(), "${", "}",
                map(), map()).batchSize(batchSize);
    }

    @Nested
    @DisplayName("执行")
    class Execution {

        @Test
        @DisplayName("单条语句被执行")
        void executesSingleStatement() throws Exception {
            List<String> captured = JdbcFakes.newCapture();
            MigrationExecutor exec = executor("V1__init.sql", "CREATE TABLE t(id INT);");
            exec.execute(JdbcFakes.recordingConnection(captured));
            assertThat(captured).containsExactly("CREATE TABLE t(id INT)");
        }

        @Test
        @DisplayName("多条语句按顺序逐条执行")
        void executesMultipleStatementsInOrder() throws Exception {
            List<String> captured = JdbcFakes.newCapture();
            MigrationExecutor exec = executor("V1.sql",
                    "CREATE TABLE a(id INT);\nINSERT INTO a VALUES (1);\nCREATE INDEX idx ON a(id);");
            exec.execute(JdbcFakes.recordingConnection(captured));
            assertThat(captured).containsExactly(
                    "CREATE TABLE a(id INT)",
                    "INSERT INTO a VALUES (1)",
                    "CREATE INDEX idx ON a(id)");
        }

        @Test
        @DisplayName("按时间或语句数周期报告已确认执行进度、耗时与速率")
        void reportsConfirmedStatementProgress() throws Exception {
            List<String> logs = new ArrayList<String>();
            AtomicLong now = new AtomicLong(-1_000_000_000L);
            SqlMigrationExecutor exec = (SqlMigrationExecutor) executor("V1__progress.sql",
                    "SELECT 1;\nSELECT 2;\nSELECT 3;");
            exec.reportProgressTo(recordingLog(logs),
                    () -> now.addAndGet(1_000_000_000L), Long.MAX_VALUE, 2);

            exec.execute(JdbcFakes.recordingConnection(JdbcFakes.newCapture()));

            assertThat(logs).containsExactly(
                    "迁移语句进度 V1__progress.sql：JDBC 已确认执行 2/3 条，"
                            + "耗时 2.0 秒，平均速率 1.0 条/秒",
                    "迁移语句进度 V1__progress.sql：JDBC 已确认执行 3/3 条，"
                            + "耗时 3.0 秒，平均速率 1.0 条/秒");
        }

        @Test
        @DisplayName("未达到语句阈值时仍按时间周期报告进度")
        void reportsProgressAfterTimeInterval() throws Exception {
            List<String> logs = new ArrayList<String>();
            AtomicLong now = new AtomicLong(-1_000_000_000L);
            SqlMigrationExecutor exec = (SqlMigrationExecutor) executor("V2__slow.sql",
                    "SELECT 1;\nSELECT 2;\nSELECT 3;");
            exec.reportProgressTo(recordingLog(logs),
                    () -> now.addAndGet(1_000_000_000L), 2_000_000_000L, Integer.MAX_VALUE);

            exec.execute(JdbcFakes.recordingConnection(JdbcFakes.newCapture()));

            assertThat(logs).extracting(message -> message.substring(
                    message.indexOf("JDBC 已确认执行")))
                    .containsExactly(
                            "JDBC 已确认执行 2/3 条，耗时 2.0 秒，平均速率 1.0 条/秒",
                            "JDBC 已确认执行 3/3 条，耗时 3.0 秒，平均速率 1.0 条/秒");
        }

        @Test
        @DisplayName("单条 SQL 尚未返回时仍周期报告存活进度且不增加确认数")
        void reportsHeartbeatWhileStatementIsInFlight() throws Exception {
            List<String> logs = new ArrayList<String>();
            CountDownLatch statementStarted = new CountDownLatch(1);
            CountDownLatch releaseStatement = new CountDownLatch(1);
            CountDownLatch progressReported = new CountDownLatch(1);
            SqlMigrationExecutor exec = (SqlMigrationExecutor) executor("V2_1__long_ddl.sql",
                    "CREATE TABLE slow_table(id INT);");
            exec.reportProgressTo(new Log() {
                @Override public void debug(String message) { }
                @Override public void info(String message) {
                    logs.add(message);
                    progressReported.countDown();
                }
                @Override public void warn(String message) { }
                @Override public void error(String message, Throwable error) { }
            }, System::nanoTime, TimeUnit.MILLISECONDS.toNanos(20), Integer.MAX_VALUE);

            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> execution = worker.submit(() -> {
                    exec.execute(JdbcFakes.blockingConnection(
                            statementStarted, releaseStatement));
                    return null;
                });

                assertThat(statementStarted.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(progressReported.await(1, TimeUnit.SECONDS)).isTrue();
                releaseStatement.countDown();
                execution.get(1, TimeUnit.SECONDS);
            } finally {
                releaseStatement.countDown();
                worker.shutdownNow();
            }

            assertThat(logs).anySatisfy(message -> assertThat(message)
                    .contains("V2_1__long_ddl.sql")
                    .contains("JDBC 已确认执行 0/1 条"));
        }

        @Test
        @DisplayName("短小脚本未达到周期阈值时不增加语句级完成噪声")
        void shortScriptDoesNotEmitPeriodicProgress() throws Exception {
            List<String> logs = new ArrayList<String>();
            SqlMigrationExecutor exec = (SqlMigrationExecutor) executor("V3__small.sql",
                    "SELECT 1;");
            exec.reportProgressTo(recordingLog(logs), () -> 0L,
                    10_000_000_000L, 1000);

            exec.execute(JdbcFakes.recordingConnection(JdbcFakes.newCapture()));

            assertThat(logs).isEmpty();
        }

        @Test
        @DisplayName("空脚本（仅注释/空白）不执行任何语句")
        void emptyScriptExecutesNothing() throws Exception {
            List<String> captured = JdbcFakes.newCapture();
            MigrationExecutor exec = executor("V1.sql", "-- just a comment\n   \n");
            exec.execute(JdbcFakes.recordingConnection(captured));
            assertThat(captured).isEmpty();
        }

        @Test
        @DisplayName("占位符在词法解析之前替换")
        void placeholdersReplacedBeforeLexing() throws Exception {
            List<String> captured = JdbcFakes.newCapture();
            MigrationExecutor exec = new SqlMigrationExecutor("V1.sql",
                    "CREATE TABLE ${name}(id INT);",
                    SqlStatementBuilderConfig.postgresql(), "${", "}",
                    map("name", "orders"), map());
            exec.execute(JdbcFakes.recordingConnection(captured));
            assertThat(captured).containsExactly("CREATE TABLE orders(id INT)");
        }
    }

    @Nested
    @DisplayName("失败语义")
    class Failure {

        @Test
        @DisplayName("失败时抛 FLYDB-2010，携带脚本名/语句序号/起始行号/驱动错误消息")
        void failureCarriesScriptIndexLineAndDriverMessage() {
            // 第 2 条语句（INSERT，起始行号 2）失败
            List<String> captured = JdbcFakes.newCapture();
            SQLException driverError = new SQLException("column zzz does not exist", "42703");
            SqlMigrationExecutor exec = (SqlMigrationExecutor) executor("V2__add.sql",
                    "CREATE TABLE a(id INT);\nINSERT INTO a(zzz) VALUES (1);");
            assertThatThrownBy(() -> exec.execute(
                    JdbcFakes.failingConnection(captured, "INSERT INTO a(zzz)", driverError)))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> {
                        FlydbException fe = (FlydbException) ex;
                        assertThat(fe.errorCode()).isEqualTo(ErrorCode.MIGRATION_EXECUTION_FAILED);
                        String msg = fe.getMessage();
                        assertThat(msg).contains("V2__add.sql");   // 脚本名
                        assertThat(msg).contains("第 2 条");        // 语句序号
                        assertThat(msg).contains("行 2");           // 起始行号
                        assertThat(msg).contains("column zzz does not exist"); // 驱动原始错误
                        assertThat(fe.getCause()).isSameAs(driverError);
                    });
            // 第 1 条已成功执行
            assertThat(captured).containsExactly("CREATE TABLE a(id INT)");
            assertThat(exec.statementExecutionSnapshot())
                    .contains("JDBC 已确认执行 1/2 条")
                    .contains("不代表事务已提交")
                    .contains("第 2 条")
                    .contains("起始行 2")
                    .contains("逐条执行");
        }

        @Test
        @DisplayName("失败后不继续执行后续语句")
        void stopsOnFirstFailure() {
            List<String> captured = JdbcFakes.newCapture();
            SQLException driverError = new SQLException("boom");
            MigrationExecutor exec = executor("V1.sql",
                    "SELECT 1;\nSELECT 2;\nSELECT 3;");
            assertThatThrownBy(() -> exec.execute(
                    JdbcFakes.failingConnection(captured, "SELECT 2", driverError)))
                    .isInstanceOf(FlydbException.class);
            assertThat(captured).containsExactly("SELECT 1"); // 仅第 1 条
        }
    }

    @Nested
    @DisplayName("JDBC batch")
    class JdbcBatch {

        @Test
        @DisplayName("batch-size>1 时语句经 addBatch/executeBatch 提交，尾部不满一批也会执行")
        void executesStatementsThroughExecuteBatch() throws Exception {
            List<String> captured = JdbcFakes.newCapture();
            MigrationExecutor exec = batchExecutor("V1.sql",
                    "SELECT 1;\nSELECT 2;\nSELECT 3;\nSELECT 4;\nSELECT 5;", 2);
            exec.execute(JdbcFakes.batchingConnection(captured, null));
            // batchingConnection 只在 executeBatch 时记录，captured 非空即证明走的是 batch 路径
            assertThat(captured).containsExactly(
                    "SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4", "SELECT 5");
        }

        @Test
        @DisplayName("批内失败报 FLYDB-2010，序号与起始行按批内已执行计数推算")
        void batchFailureReportsInferredIndexAndLine() {
            List<String> captured = JdbcFakes.newCapture();
            SqlMigrationExecutor exec = batchExecutor("V2__add.sql",
                    "CREATE TABLE a(id INT);\nINSERT INTO bad VALUES (1);\nSELECT 3;", 2);
            assertThatThrownBy(() -> exec.execute(
                    JdbcFakes.batchingConnection(captured, "INSERT INTO bad")))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> {
                        FlydbException fe = (FlydbException) ex;
                        assertThat(fe.errorCode()).isEqualTo(ErrorCode.MIGRATION_EXECUTION_FAILED);
                        String msg = fe.getMessage();
                        assertThat(msg).contains("V2__add.sql");
                        assertThat(msg).contains("第 2 条");          // 起始 1 + 已执行 1
                        assertThat(msg).contains("行 2");             // INSERT 的起始行号
                        assertThat(msg).contains("batch-size=2");
                        assertThat(msg).contains("synthetic batch failure");
                    });
            // 同批中失败前的语句已应用，后续批次不再执行
            assertThat(captured).containsExactly("CREATE TABLE a(id INT)");
            assertThat(exec.statementExecutionSnapshot())
                    .contains("JDBC 已确认执行 1/3 条")
                    .contains("按 JDBC 已返回计数推算为第 2 条")
                    .contains("不是驱动明确失败标记");
        }

        @Test
        @DisplayName("遇错继续型驱动按 EXECUTE_FAILED 标记定位批内失败语句")
        void continuingBatchFailureUsesExecuteFailedMarker() {
            List<String> captured = JdbcFakes.newCapture();
            SqlMigrationExecutor exec = batchExecutor("V3__data.sql",
                    "SELECT 1;\nSELECT bad;\nSELECT 3;\nCOMMENT ON TABLE t IS 'ok';", 4);

            assertThatThrownBy(() -> exec.execute(
                    JdbcFakes.continuingBatchConnection(captured, "SELECT bad")))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> {
                        FlydbException fe = (FlydbException) ex;
                        assertThat(fe.errorCode()).isEqualTo(ErrorCode.MIGRATION_EXECUTION_FAILED);
                        assertThat(fe.getMessage()).contains("第 2 条");
                        assertThat(fe.getMessage()).contains("行 2");
                        assertThat(fe.getMessage()).contains("synthetic continuing batch failure");
                    });
            assertThat(captured).containsExactly("SELECT 1", "SELECT 3",
                    "COMMENT ON TABLE t IS 'ok'");
            assertThat(exec.statementExecutionSnapshot())
                    .contains("JDBC 已确认执行 1/4 条")
                    .contains("第 2 条")
                    .contains("JDBC EXECUTE_FAILED 明确标记");
        }

        @Test
        @DisplayName("批内首条失败时不把失败后的成功项计入确认前缀")
        void firstBatchFailureDoesNotInflateConfirmedPrefix() {
            SqlMigrationExecutor exec = batchExecutor("V3_1__first_failure.sql",
                    "INSERT INTO bad VALUES (1);\nINSERT INTO later VALUES (2);", 2);

            assertThatThrownBy(() -> exec.execute(
                    JdbcFakes.batchFailureWithUpdateCounts(
                            Statement.EXECUTE_FAILED, 1)))
                    .isInstanceOf(FlydbException.class);

            assertThat(exec.statementExecutionSnapshot())
                    .contains("JDBC 已确认执行 0/2 条")
                    .contains("第 1 条")
                    .contains("JDBC EXECUTE_FAILED 明确标记");
        }

        @Test
        @DisplayName("只统计首个明确失败之前的标准成功前缀")
        void countsOnlyStandardSuccessfulUpdateCounts() {
            SqlMigrationExecutor exec = batchExecutor("V3_1__counts.sql",
                    "SELECT unknown;\nSELECT bad;\nSELECT successful;", 3);

            assertThatThrownBy(() -> exec.execute(
                    JdbcFakes.batchFailureWithUpdateCounts(
                            -4, Statement.EXECUTE_FAILED, Statement.SUCCESS_NO_INFO)))
                    .isInstanceOf(FlydbException.class);

            assertThat(exec.statementExecutionSnapshot())
                    .contains("JDBC 已确认执行 0/3 条")
                    .contains("第 2 条")
                    .contains("JDBC EXECUTE_FAILED 明确标记");
        }

        @Test
        @DisplayName("驱动未给失败标记时只报告批次范围")
        void unmarkedBatchFailureReportsRangeInsteadOfInventedLine() {
            List<String> captured = JdbcFakes.newCapture();
            SqlMigrationExecutor exec = batchExecutor("V4__data.sql",
                    "SELECT 1;\nSELECT bad;\nCOMMENT ON TABLE t IS 'ok';", 3);

            assertThatThrownBy(() -> exec.execute(
                    JdbcFakes.unmarkedFailingBatchConnection(captured, "SELECT bad")))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> {
                        String msg = ((FlydbException) ex).getMessage();
                        assertThat(msg).contains("第 1-3 条语句批量执行失败");
                        assertThat(msg).contains("无法可靠定位具体语句与行号");
                        assertThat(msg).doesNotContain("第 4 条");
                    });
            assertThat(exec.statementExecutionSnapshot())
                    .contains("JDBC 已确认执行 0/3 条")
                    .contains("无法可靠定位具体语句")
                    .contains("候选批次为第 1-3 条");
        }
    }

    private static Log recordingLog(List<String> messages) {
        return new Log() {
            @Override public void debug(String message) { messages.add(message); }
            @Override public void info(String message) { messages.add(message); }
            @Override public void warn(String message) { messages.add(message); }
            @Override public void error(String message, Throwable error) { messages.add(message); }
        };
    }
}
