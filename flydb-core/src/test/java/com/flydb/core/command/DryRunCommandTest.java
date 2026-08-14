package com.flydb.core.command;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.Flydb;
import com.flydb.core.api.DryRunResult;
import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("关闭占位符替换后 dry-run 原样保留业务运行时模板")
    void preservesRuntimeTemplatesWhenPlaceholderReplacementIsDisabled() throws Exception {
        Files.write(migrations.resolve("V1__runtime_template.sql"),
                "INSERT INTO config(template) VALUES ('${workDate}');"
                        .getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .placeholderReplacement(false)
                .build());

        DryRunResult result = flydb.dryRunMigrate();

        assertThat(result.migrations()).singleElement().satisfies(migration ->
                assertThat(migration.statements()).extracting("sql").containsExactly(
                        "INSERT INTO config(template) VALUES ('${workDate}')"));
    }

    @Test
    @DisplayName("指定 targetVersion 时只预演该版本且不夹带 repeatable")
    void previewsOnlyExactTargetVersion() throws Exception {
        Files.write(migrations.resolve("V1__one.sql"), "SELECT 1;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V2__two.sql"), "SELECT 2;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V3__three.sql"), "SELECT 3;".getBytes("UTF-8"));
        Files.write(migrations.resolve("R__view.sql"), "SELECT 4;".getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .targetVersion("2")
                .build());

        DryRunResult result = flydb.dryRunMigrate();

        assertThat(result.migrations()).extracting("script")
                .containsExactly("V2__two.sql");
    }

    @Test
    @DisplayName("startVersion/endVersion 按包含边界预演版本范围")
    void previewsInclusiveVersionRange() throws Exception {
        Files.write(migrations.resolve("V1__one.sql"), "SELECT 1;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V2__two.sql"), "SELECT 2;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V3__three.sql"), "SELECT 3;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V4__four.sql"), "SELECT 4;".getBytes("UTF-8"));
        Files.write(migrations.resolve("R__view.sql"), "SELECT 5;".getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .startVersion("2")
                .endVersion("3")
                .build());

        DryRunResult result = flydb.dryRunMigrate();

        assertThat(result.migrations()).extracting("script")
                .containsExactly("V2__two.sql", "V3__three.sql");
    }

    @Test
    @DisplayName("版本族范围包含区间内的字母连字符版本和结束族子版本")
    void familyRangeIncludesAlphanumericVersionsAndEndFamilyDescendants() throws Exception {
        Files.write(migrations.resolve("V20260101.1__start.sql"), "SELECT 1;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V20260327-b06.4__data.sql"), "SELECT 2;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V20260327-b07.5__data.sql"), "SELECT 3;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V20260625.3__end.sql"), "SELECT 4;".getBytes("UTF-8"));
        Files.write(migrations.resolve("V20260626__outside.sql"), "SELECT 5;".getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .versionSelection("family-range")
                .startVersion("20260101")
                .endVersion("20260625")
                .build());

        assertThat(flydb.dryRunMigrate().migrations()).extracting("script").containsExactly(
                "V20260101.1__start.sql",
                "V20260327-b06.4__data.sql",
                "V20260327-b07.5__data.sql",
                "V20260625.3__end.sql");
    }

    @Test
    @DisplayName("递归发现的脚本能按相对路径读取并预演")
    void previewsMigrationFromNestedDirectory() throws Exception {
        Path nested = Files.createDirectories(migrations.resolve("tenant/a"));
        Files.write(nested.resolve("V1__nested.sql"), "SELECT 1;".getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(configuration(dataSource));

        DryRunResult result = flydb.dryRunMigrate();

        assertThat(result.migrations()).singleElement().satisfies(migration -> {
            assertThat(migration.script()).isEqualTo("tenant/a/V1__nested.sql");
            assertThat(migration.statements()).extracting("sql").containsExactly("SELECT 1");
        });
    }

    @Test
    @DisplayName("精确目录版本会选择该目录下全部子脚本，同时保留默认精确文件版本语义")
    void previewsAllScriptsFromAnExactDirectoryVersion() throws Exception {
        Path selected = Files.createDirectories(migrations.resolve("mysql/param/20230531"));
        Path next = Files.createDirectories(migrations.resolve("mysql/param/20230727"));
        Files.write(selected.resolve("V20230531.1__one.sql"), "SELECT 1;".getBytes("UTF-8"));
        Files.write(selected.resolve("V20230531.2__two.sql"), "SELECT 2;".getBytes("UTF-8"));
        Files.write(selected.resolve("V20230531.3__three.sql"), "SELECT 3;".getBytes("UTF-8"));
        Files.write(next.resolve("V20230727.1__next.sql"), "SELECT 4;".getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .versionSource("directory")
                .targetVersion("20230531")
                .migrationOrder("directory-version")
                .build());

        DryRunResult result = flydb.dryRunMigrate();

        assertThat(result.migrations()).extracting("script").containsExactly(
                "mysql/param/20230531/V20230531.1__one.sql",
                "mysql/param/20230531/V20230531.2__two.sql",
                "mysql/param/20230531/V20230531.3__three.sql");
    }

    @Test
    @DisplayName("指定的精确版本在本地不存在时报 FLYDB-2001")
    void rejectsMissingExactTargetVersion() throws Exception {
        Files.write(migrations.resolve("V1__one.sql"), "SELECT 1;".getBytes("UTF-8"));
        InMemoryFlydbDataSource dataSource = new InMemoryFlydbDataSource(false);
        Flydb flydb = new Flydb(FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .targetVersion("9")
                .build());

        assertThatThrownBy(flydb::dryRunMigrate)
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.INVALID_VERSION);
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
