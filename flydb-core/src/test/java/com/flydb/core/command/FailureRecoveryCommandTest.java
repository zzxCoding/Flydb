package com.flydb.core.command;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.RepairResult;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("命令失败恢复契约")
class FailureRecoveryCommandTest {

    @TempDir
    Path migrations;

    @Test
    @DisplayName("PG 失败回滚无历史记录，修复脚本后可直接重跑")
    void postgresFailureIsSelfHealing() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(true);
        write("V1__init.sql", "CREATE TABLE ok(id INT); BROKEN;");
        FlydbConfiguration cfg = configuration(dataSource);

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("V1__init.sql");
        assertThat(dataSource.history()).isEmpty();
        assertThat(dataSource.rollbacks()).isEqualTo(1);

        write("V1__init.sql", "CREATE TABLE ok(id INT);");
        MigrateResult result = new MigrateCommand(cfg).execute();
        assertThat(result.executed()).containsExactly("V1__init.sql");
        assertThat(dataSource.history()).hasSize(1);
    }

    @Test
    @DisplayName("MySQL 失败写 FAILED 并阻断，repair 后可重跑")
    void mysqlFailureRequiresRepair() throws Exception {
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        write("V1__init.sql", "CREATE TABLE ok(id INT); BROKEN;");
        FlydbConfiguration cfg = configuration(dataSource);

        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class);
        assertThat(dataSource.history()).singleElement()
                .satisfies(row -> assertThat(row.get("success")).isEqualTo(false));
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
}
