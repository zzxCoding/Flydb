package com.flydb.core.executor;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
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
            MigrationExecutor exec = executor("V2__add.sql",
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
            MigrationExecutor exec = batchExecutor("V2__add.sql",
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
        }

        @Test
        @DisplayName("遇错继续型驱动按 EXECUTE_FAILED 标记定位批内失败语句")
        void continuingBatchFailureUsesExecuteFailedMarker() {
            List<String> captured = JdbcFakes.newCapture();
            MigrationExecutor exec = batchExecutor("V3__data.sql",
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
        }

        @Test
        @DisplayName("驱动未给失败标记时只报告批次范围")
        void unmarkedBatchFailureReportsRangeInsteadOfInventedLine() {
            List<String> captured = JdbcFakes.newCapture();
            MigrationExecutor exec = batchExecutor("V4__data.sql",
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
        }
    }
}
