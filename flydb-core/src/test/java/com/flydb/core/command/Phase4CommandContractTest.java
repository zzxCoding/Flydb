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
