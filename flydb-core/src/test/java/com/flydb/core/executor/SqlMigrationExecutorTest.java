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
}
