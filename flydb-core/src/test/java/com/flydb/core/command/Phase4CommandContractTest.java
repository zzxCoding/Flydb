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
import com.flydb.core.api.RepairResult;
import com.flydb.core.api.UndoResult;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationState;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.log.Log;
import com.flydb.core.log.LogCreator;
import com.flydb.core.log.LogFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("阶段 4 命令契约")
class Phase4CommandContractTest {

    @TempDir
    Path migrations;

    @AfterEach
    void resetLogFactory() {
        LogFactory.setLogCreator(null);
    }

    @Test
    @DisplayName("baseline 仅允许空历史表并产生 BASELINE 状态")
    void baselineRequiresEmptyHistory() {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(true);
        FlydbConfiguration cfg = configuration(dataSource, true);

        new BaselineCommand(cfg).execute();
        assertThat(new InfoCommand(cfg).execute().all()).singleElement()
                .satisfies(info -> assertThat(info.state()).isEqualTo(MigrationState.BASELINE));
        assertThatThrownBy(() -> new BaselineCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.BASELINE_PRECONDITION_UNMET);
    }

    @Test
    @DisplayName("repair 对齐修改后的版本化脚本 checksum")
    void repairAlignsChecksum() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(true);
        write("V1__init.sql", "CREATE TABLE t(id INT);");
        FlydbConfiguration cfg = configuration(dataSource, true);
        new MigrateCommand(cfg).execute();
        write("V1__init.sql", "CREATE TABLE t(id BIGINT);");

        assertThatThrownBy(() -> new ValidateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.CHECKSUM_MISMATCH);
        RepairResult result = new RepairCommand(cfg).execute();
        assertThat(result.alignedChecksums()).containsExactly("V1__init.sql");
        assertThatCode(() -> new ValidateCommand(cfg).execute()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("undo 仅撤销最高版本并使其下次 migrate 重新执行")
    void undoAppendsAuditAndMakesVersionPendingAgain() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(true);
        write("V1__init.sql", "CREATE TABLE t(id INT);");
        write("U1__init.sql", "DROP TABLE t;");
        FlydbConfiguration cfg = configuration(dataSource, true);
        new MigrateCommand(cfg).execute();

        UndoResult result = new UndoCommand(cfg).execute();

        assertThat(result.undoneVersion()).isEqualTo(MigrationVersion.parse("1"));
        assertThat(new InfoCommand(cfg).execute().all()).singleElement()
                .satisfies(info -> assertThat(info.state()).isEqualTo(MigrationState.UNDONE));
        assertThat(new MigrateCommand(cfg).execute().executed()).containsExactly("V1__init.sql");
    }

    @Test
    @DisplayName("显式开启 clean 后删除当前 schema 对象及历史记录")
    void cleanDropsSchemaObjects() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V1__init.sql", "CREATE TABLE t(id INT);");
        FlydbConfiguration cfg = configuration(dataSource, false);
        new MigrateCommand(cfg).execute();

        new CleanCommand(cfg).execute();

        assertThat(dataSource.history()).isEmpty();
        assertThat(dataSource.executedSql()).anyMatch(sql ->
                sql.startsWith("DROP TABLE") && sql.contains("flydb_schema_history"));
    }

    @Test
    @DisplayName("clean 输出对象统计、逐对象进度和记账表清理日志")
    void cleanReportsDetailedProgress() throws Exception {
        List<String> logs = new ArrayList<String>();
        LogFactory.setLogCreator(new RecordingLogCreator(logs));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        FlydbConfiguration cfg = configuration(dataSource, false);

        new CleanCommand(cfg).execute();

        assertThat(logs).contains(
                "开始清理 schema test",
                "发现待清理对象：视图 0，表 1，序列 0",
                "正在删除表 1/1: app_table",
                "正在删除历史表: flydb_schema_history",
                "正在删除锁表: flydb_schema_lock",
                "clean 完成：schema test");
    }

    @Test
    @DisplayName("migrate 真实执行只包含指定版本范围")
    void migrateExecutesOnlySelectedVersionRange() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V1__one.sql", "SELECT 1;");
        write("V2__two.sql", "SELECT 2;");
        write("V3__three.sql", "SELECT 3;");
        write("R__view.sql", "SELECT 4;");
        FlydbConfiguration configuration = FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .startVersion("2")
                .endVersion("3")
                .build();

        assertThat(new MigrateCommand(configuration).execute().executed())
                .containsExactly("V2__two.sql", "V3__three.sql");
        assertThat(dataSource.executedSql()).contains("SELECT 2", "SELECT 3")
                .doesNotContain("SELECT 1", "SELECT 4");
    }

    @Test
    @DisplayName("clean/baseline 不解析迁移集合，目录中的非法文件名不阻断")
    void cleanAndBaselineSkipMigrationResolution() throws Exception {
        write("Vbroken.sql", "SELECT 1;"); // V 前缀 + .sql 但无法解析 → 扫描必报 FLYDB-2001
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        FlydbConfiguration cfg = configuration(dataSource, false);

        assertThatCode(() -> new CleanCommand(cfg).execute()).doesNotThrowAnyException();
        assertThatCode(() -> new BaselineCommand(cfg).execute()).doesNotThrowAnyException();
        // 对比：依赖迁移集合的 migrate 仍会被阻断，证明惰性发现没有吞掉校验
        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.INVALID_VERSION);
    }

    @Test
    @DisplayName("migrate 逐脚本输出进度与耗时日志")
    void migrateReportsPerScriptProgress() throws Exception {
        List<String> logs = new ArrayList<String>();
        LogFactory.setLogCreator(new RecordingLogCreator(logs));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(true);
        write("V1__one.sql", "SELECT 1;");
        write("V2__two.sql", "SELECT 2;");
        FlydbConfiguration cfg = configuration(dataSource, true);

        new MigrateCommand(cfg).execute();

        assertThat(logs).anyMatch(message ->
                message.startsWith("正在执行迁移 1/2: V1__one.sql"));
        assertThat(logs).anyMatch(message ->
                message.startsWith("完成迁移 2/2: V2__two.sql") && message.contains("耗时"));
    }

    @Test
    @DisplayName("range 结束版本排除族子版本时输出 family-range 提示")
    void rangeEndVersionWarnsAboutExcludedFamilyDescendants() throws Exception {
        List<String> logs = new ArrayList<String>();
        LogFactory.setLogCreator(new RecordingLogCreator(logs));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V20260625.1__schema.sql", "SELECT 1;");
        write("V20260625.3__data.sql", "SELECT 2;");
        FlydbConfiguration cfg = FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .endVersion("20260625")
                .build();

        new MigrateCommand(cfg).execute();

        assertThat(logs).anyMatch(message ->
                message.contains("range 结束版本 20260625 不含其族子版本")
                        && message.contains("20260625.3") && message.contains("family-range"));
        // 排除在 range 外的迁移没有执行（executedSql 只含历史表 DDL）
        assertThat(dataSource.executedSql()).noneMatch(sql -> sql.contains("SELECT"));
    }

    @Test
    @DisplayName("flydb.batch-size>1 时 migrate 语句经 JDBC batch 提交")
    void migrateExecutesStatementsInJdbcBatches() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V1__one.sql", "INSERT INTO app_table VALUES (1);\n"
                + "INSERT INTO app_table VALUES (2);\n"
                + "INSERT INTO app_table VALUES (3);");
        write("V2__two.sql", "INSERT INTO app_table VALUES (4);");
        FlydbConfiguration cfg = FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .batchSize(2)
                .build();

        new MigrateCommand(cfg).execute();

        assertThat(dataSource.executedSql()).containsSubsequence(
                "INSERT INTO app_table VALUES (1)",
                "INSERT INTO app_table VALUES (2)",
                "INSERT INTO app_table VALUES (3)",
                "INSERT INTO app_table VALUES (4)");
        assertThat(dataSource.batches()).isEqualTo(3); // 3 条按批 2 → 2 批；末尾 1 条 → 1 批
    }

    private FlydbConfiguration configuration(InMemoryFlydbDataSource dataSource,
                                             boolean cleanDisabled) {
        return FlydbConfiguration.builder().dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .cleanDisabled(cleanDisabled).build();
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
                @Override public void error(String message, Throwable error) { messages.add(message); }
            };
        }
    }
}
