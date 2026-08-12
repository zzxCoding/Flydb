package com.flydb.core.command;

import java.nio.file.Files;
import java.nio.file.Path;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("阶段 4 命令契约")
class Phase4CommandContractTest {

    @TempDir
    Path migrations;

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

    private FlydbConfiguration configuration(InMemoryFlydbDataSource dataSource,
                                             boolean cleanDisabled) {
        return FlydbConfiguration.builder().dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .cleanDisabled(cleanDisabled).build();
    }

    private void write(String name, String sql) throws Exception {
        Files.write(migrations.resolve(name), sql.getBytes("UTF-8"));
    }
}
