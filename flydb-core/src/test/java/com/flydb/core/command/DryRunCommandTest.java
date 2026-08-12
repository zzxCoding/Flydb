package com.flydb.core.command;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.Flydb;
import com.flydb.core.api.DryRunResult;
import com.flydb.core.api.FlydbConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DryRunCommand")
class DryRunCommandTest {

    @TempDir
    Path migrations;

    @Test
    @DisplayName("migrate 预演完成占位符替换与语句解析但不建表、不执行、不记账")
    void previewsPendingMigrationsWithoutSideEffects() throws Exception {
        Files.write(migrations.resolve("V1__init.sql"),
                ("CREATE TABLE ${table_name}(id INT);\n"
                        + "INSERT INTO ${table_name} VALUES (1);\n").getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(configuration(dataSource));

        DryRunResult result = flydb.dryRunMigrate();

        assertThat(result.migrations()).hasSize(1);
        assertThat(result.migrations().get(0).script()).isEqualTo("V1__init.sql");
        assertThat(result.migrations().get(0).statements())
                .extracting("lineNumber", "sql")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "CREATE TABLE demo(id INT)"),
                        org.assertj.core.groups.Tuple.tuple(2, "INSERT INTO demo VALUES (1)"));
        assertThat(dataSource.executedSql()).isEmpty();
        assertThat(dataSource.history()).isEmpty();
    }

    @Test
    @DisplayName("undo 预演只解析最近版本对应的 U 脚本")
    void previewsLatestUndoWithoutExecutingIt() throws Exception {
        Files.write(migrations.resolve("V1__init.sql"),
                "CREATE TABLE demo(id INT);".getBytes("UTF-8"));
        Files.write(migrations.resolve("U1__init.sql"),
                "DROP TABLE demo;".getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(configuration(dataSource));
        flydb.migrate();

        DryRunResult result = flydb.dryRunUndo();

        assertThat(result.migrations()).hasSize(1);
        assertThat(result.migrations().get(0).script()).isEqualTo("U1__init.sql");
        assertThat(result.migrations().get(0).statements().get(0).sql())
                .isEqualTo("DROP TABLE demo");
        assertThat(dataSource.executedSql()).doesNotContain("DROP TABLE demo");
        assertThat(dataSource.history()).hasSize(1);
    }

    private FlydbConfiguration configuration(InMemoryFlydbDataSource dataSource) {
        java.util.Map<String, String> placeholders = new java.util.HashMap<String, String>();
        placeholders.put("table_name", "demo");
        return FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .placeholders(placeholders)
                .build();
    }
}
