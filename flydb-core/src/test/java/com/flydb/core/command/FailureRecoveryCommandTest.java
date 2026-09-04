package com.flydb.core.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.RepairResult;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.log.Log;
import com.flydb.core.log.LogCreator;
import com.flydb.core.log.LogFactory;
import com.flydb.core.migration.MigrationState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("命令失败恢复契约")
class FailureRecoveryCommandTest {

    @TempDir
    Path migrations;

    @AfterEach
    void resetLogFactory() {
        LogFactory.setLogCreator(null);
    }

    @Test
    @DisplayName("PG 失败回滚无历史记录，修复脚本后可直接重跑")
    void postgresFailureIsSelfHealing() throws Exception {
        List<String> logs = new ArrayList<String>();
        LogFactory.setLogCreator(new RecordingLogCreator(logs));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(true);
        write("V1__init.sql", "CREATE TABLE ok(id INT); BROKEN;");
        FlydbConfiguration cfg = configuration(dataSource);

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("V1__init.sql");
        assertThat(dataSource.history()).isEmpty();
        assertThat(dataSource.rollbacks()).isEqualTo(1);
        assertThat(logs).anyMatch(message -> message.contains("迁移失败执行快照")
                && message.contains("事务模式：单脚本事务")
                && message.contains("JDBC 已确认执行 1/2 条")
                && message.contains("事务结果：已回滚"));

        write("V1__init.sql", "CREATE TABLE ok(id INT);");
        MigrateResult result = new MigrateCommand(cfg).execute();
        assertThat(result.executed()).containsExactly("V1__init.sql");
        assertThat(dataSource.history()).hasSize(1);
    }

    @Test
    @DisplayName("MySQL 失败写 FAILED 并阻断，repair 后可重跑")
    void mysqlFailureRequiresRepair() throws Exception {
        List<String> logs = new ArrayList<String>();
        LogFactory.setLogCreator(new RecordingLogCreator(logs));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V1__init.sql", "-- DDL 脚本表头\nCREATE TABLE ok(id INT); BROKEN;");
        FlydbConfiguration cfg = configuration(dataSource);

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class);
        assertThat(dataSource.history()).singleElement()
                .satisfies(row -> assertThat(row.get("success")).isEqualTo(false));
        assertThat(logs).anyMatch(message -> message.contains("迁移失败执行快照")
                && message.contains("事务模式：非事务执行")
                && message.contains("JDBC 已确认执行 1/2 条")
                && message.contains("事务结果：未执行整体回滚")
                && message.contains("数据库状态需人工核验"));
        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR);

        RepairResult repair = new RepairCommand(cfg).execute();
        assertThat(repair.removedFailedRecords()).containsExactly("V1__init.sql");
        write("V1__init.sql", "CREATE TABLE ok(id INT);");
        assertThat(new MigrateCommand(cfg).execute().executed())
                .containsExactly("V1__init.sql");
    }

    @Test
    @DisplayName("非事务 batch 首条失败时快照确认数不包含失败后的返回项")
    void nonTransactionalBatchFirstFailureReportsZeroConfirmedPrefix() throws Exception {
        List<String> logs = new ArrayList<String>();
        LogFactory.setLogCreator(new RecordingLogCreator(logs));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false, true);
        write("V1__first_failure.sql", "BROKEN; SELECT 1;");
        FlydbConfiguration cfg = FlydbConfiguration.builder().dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .batchSize(2)
                .build();

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class);

        assertThat(dataSource.history()).singleElement()
                .satisfies(row -> assertThat(row.get("success")).isEqualTo(false));
        assertThat(logs).anyMatch(message -> message.contains("迁移失败执行快照")
                && message.contains("事务模式：非事务执行")
                && message.contains("JDBC 已确认执行 0/2 条")
                && message.contains("第 1 条")
                && message.contains("JDBC EXECUTE_FAILED 明确标记"));
    }

    @Test
    @DisplayName("单脚本事务 batch 首条失败时同样报告零确认并完成回滚")
    void transactionalBatchFirstFailureReportsZeroConfirmedPrefixAndRollsBack()
            throws Exception {
        List<String> logs = new ArrayList<String>();
        LogFactory.setLogCreator(new RecordingLogCreator(logs));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false, true);
        write("V1__first_dml_failure.sql", "INSERT INTO BROKEN VALUES (1); "
                + "INSERT INTO app_table VALUES (2);");
        FlydbConfiguration cfg = FlydbConfiguration.builder().dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .batchSize(2)
                .build();

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class);

        assertThat(dataSource.history()).isEmpty();
        assertThat(dataSource.rollbacks()).isEqualTo(1);
        assertThat(logs).anyMatch(message -> message.contains("迁移失败执行快照")
                && message.contains("事务模式：单脚本事务")
                && message.contains("JDBC 已确认执行 0/2 条")
                && message.contains("第 1 条")
                && message.contains("事务结果：已回滚"));
    }

    @Test
    @DisplayName("不支持 DDL 事务的数据库仍对纯 DML 脚本整体提交并在失败时自愈")
    void pureDmlFailureIsSelfHealingOnNonTransactionalDdlDatabase() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V1__data.sql", "INSERT INTO app_table VALUES (1);\n"
                + "UPDATE app_table SET id = 2 WHERE id = 1;\n"
                + "DELETE FROM app_table WHERE id = 2;\n"
                + "MERGE INTO BROKEN_TABLE target USING app_table source ON (1 = 1) "
                + "WHEN MATCHED THEN UPDATE SET target.id = source.id;");
        FlydbConfiguration cfg = configuration(dataSource);

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("V1__data.sql");
        assertThat(dataSource.history()).isEmpty();
        assertThat(dataSource.rollbacks()).isEqualTo(1);

        write("V1__data.sql", "INSERT INTO app_table VALUES (1);\n"
                + "UPDATE app_table SET id = 2 WHERE id = 1;\n"
                + "DELETE FROM app_table WHERE id = 2;\n"
                + "MERGE INTO app_table target USING app_table source ON (1 = 1) "
                + "WHEN MATCHED THEN UPDATE SET target.id = source.id;");
        assertThat(new MigrateCommand(cfg).execute().executed())
                .containsExactly("V1__data.sql");
        assertThat(dataSource.history()).singleElement()
                .satisfies(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    @DisplayName("前导表头注释不改变纯 DML 脚本的事务语义")
    void leadingCommentsDoNotDisablePureDmlTransaction() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V1__commented_data.sql", "INSERT INTO app_table VALUES (1);\n"
                + "-- ========================================\n"
                + "-- 表名：app_table\n"
                + "-- ========================================\n"
                + "/* 更新该表的测试数据 */\n"
                + "# MySQL 数据修正\n"
                + "UPDATE BROKEN_TABLE SET id = 2 WHERE id = 1;");
        FlydbConfiguration cfg = configuration(dataSource);

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("V1__commented_data.sql");
        assertThat(dataSource.history()).isEmpty();
        assertThat(dataSource.rollbacks()).isEqualTo(1);
    }

    @Test
    @DisplayName("成功迁移可由 info 观察且重跑幂等")
    void successfulMigrateIsVisibleAndIdempotent() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(true);
        write("V1__init.sql", "CREATE TABLE ok(id INT);");
        FlydbConfiguration cfg = configuration(dataSource);

        assertThat(new MigrateCommand(cfg).execute().executed()).containsExactly("V1__init.sql");
        assertThat(new MigrateCommand(cfg).execute().executed()).isEmpty();
        assertThat(new InfoCommand(cfg).execute().all()).singleElement()
                .satisfies(info -> assertThat(info.state()).isEqualTo(MigrationState.SUCCESS));
    }

    private FlydbConfiguration configuration(InMemoryFlydbDataSource dataSource) {
        return FlydbConfiguration.builder().dataSource(dataSource)
                .locations("filesystem:" + migrations).build();
    }

    private void write(String name, String sql) throws Exception {
        Files.write(migrations.resolve(name), sql.getBytes("UTF-8"));
    }

    private static final class RecordingLogCreator implements LogCreator {
        private final List<String> messages;

        private RecordingLogCreator(List<String> messages) {
            this.messages = messages;
        }

        @Override
        public Log createLog(Class<?> clazz) {
            return new Log() {
                @Override public void debug(String message) { messages.add(message); }
                @Override public void info(String message) { messages.add(message); }
                @Override public void warn(String message) { messages.add(message); }
                @Override public void error(String message, Throwable error) {
                    messages.add(message);
                }
            };
        }
    }
}
