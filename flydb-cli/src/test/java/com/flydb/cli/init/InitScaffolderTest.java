package com.flydb.cli.init;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InitScaffolder")
class InitScaffolderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("非交互 init 生成配置、首个迁移脚本与驱动指引且拒绝覆盖")
    void createsCompleteScaffoldWithoutOverwriting() throws Exception {
        List<Path> created = new InitScaffolder().create(temporaryDirectory,
                "jdbc:mysql://localhost:3306/app", "app_user", "mysql");

        assertThat(created).containsExactly(
                temporaryDirectory.resolve("flydb.conf"),
                temporaryDirectory.resolve("db/migration/V1__init.sql"),
                temporaryDirectory.resolve("drivers/README.md"));
        assertThat(read(temporaryDirectory.resolve("flydb.conf")))
                .contains("# Flydb 2.0 配置", "flydb.url=jdbc:mysql://localhost:3306/app",
                        "flydb.user=app_user", "flydb.database-type=mysql",
                        "flydb.locations=filesystem:db/migration")
                .doesNotContain("flydb.password=");
        assertThat(read(temporaryDirectory.resolve("drivers/README.md")))
                .contains("MySQL", "PostgreSQL", "达梦 DM8", "KingbaseES", "openGauss");
        assertThatThrownBy(() -> new InitScaffolder().create(temporaryDirectory,
                "jdbc:mysql://other/db", "other", "mysql"))
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("拒绝覆盖");
    }

    @Test
    @DisplayName("发行包已有驱动说明时保留原文件并继续初始化")
    void preservesDistributionDriverGuide() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("drivers"));
        Path guide = temporaryDirectory.resolve("drivers/README.md");
        Files.write(guide, "distribution guide".getBytes(StandardCharsets.UTF_8));

        List<Path> created = new InitScaffolder().create(temporaryDirectory,
                "jdbc:mysql://localhost:3306/app", "app_user", "mysql");

        assertThat(created).containsExactly(
                temporaryDirectory.resolve("flydb.conf"),
                temporaryDirectory.resolve("db/migration/V1__init.sql"));
        assertThat(read(guide)).isEqualTo("distribution guide");
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
