package com.flydb.cli.init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 生成可直接编辑的 CLI 初始目录，并且永不覆盖已有文件。 */
public final class InitScaffolder {

    public List<Path> create(Path directory, String url, String user, String databaseType) {
        Path configuration = directory.resolve("flydb.conf");
        Path migration = directory.resolve("db/migration/V1__init.sql");
        Path driverReadme = directory.resolve("drivers/README.md");
        for (Path file : Arrays.asList(configuration, migration)) {
            if (Files.exists(file)) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "目标文件已存在，拒绝覆盖: " + file);
            }
        }
        try {
            Files.createDirectories(migration.getParent());
            Files.createDirectories(driverReadme.getParent());
            write(configuration, configuration(url, user, databaseType));
            write(migration, migration());
            List<Path> files = new ArrayList<Path>();
            files.add(configuration);
            files.add(migration);
            if (!Files.exists(driverReadme)) {
                write(driverReadme, driverReadme());
                files.add(driverReadme);
            }
            return Collections.unmodifiableList(files);
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "生成 init 脚手架失败: " + e.getMessage(), e);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String configuration(String url, String user, String databaseType) {
        StringBuilder value = new StringBuilder()
                .append("# Flydb 2.0 配置（UTF-8 Properties）\n")
                .append("# 密码请通过 FLYDB_PASSWORD、${env:VAR} 或 flydb.password.file 提供，避免明文入库。\n")
                .append("flydb.url=").append(propertyValue(url)).append('\n');
        if (user != null && !user.isEmpty()) {
            value.append("flydb.user=").append(propertyValue(user)).append('\n');
        }
        if (databaseType != null && !databaseType.isEmpty()) {
            value.append("flydb.database-type=").append(propertyValue(databaseType)).append('\n');
        }
        return value.append("flydb.locations=filesystem:db/migration\n")
                .append("flydb.encoding=UTF-8\n")
                .append("flydb.table=flydb_schema_history\n")
                .append("flydb.validate-on-migrate=true\n")
                .append("flydb.clean-disabled=true\n")
                .append("flydb.lock-timeout-seconds=60\n").toString();
    }

    private static String migration() {
        return "-- Flydb 首个版本化迁移。请替换为项目所需的 DDL。\n"
                + "SELECT 1;\n";
    }

    private static String driverReadme() {
        return "# JDBC 驱动放置说明\n\n"
                + "将目标数据库的 Java 8 兼容 JDBC 驱动 jar 直接放在本目录。Flydb 不捆绑驱动，"
                + "启动时通过子类加载器扫描 `drivers/*.jar`。\n\n"
                + "| 数据库 | 驱动类 / 获取线索 |\n|---|---|\n"
                + "| MySQL / TiDB | `com.mysql.cj.jdbc.Driver`，Maven `com.mysql:mysql-connector-j` |\n"
                + "| PostgreSQL | `org.postgresql.Driver`，Maven `org.postgresql:postgresql` |\n"
                + "| Oracle | `oracle.jdbc.OracleDriver`，从 Oracle 制品库获取 `ojdbc` |\n"
                + "| openGauss | `org.opengauss.Driver`，Maven `org.opengauss:opengauss-jdbc` |\n"
                + "| KingbaseES | `com.kingbase8.Driver`，从人大金仓交付介质或内部制品库获取 |\n"
                + "| 达梦 DM8 | `dm.jdbc.driver.DmDriver`，从达梦安装目录或内部制品库获取 |\n"
                + "| OceanBase | `com.oceanbase.jdbc.Driver`，Maven `com.oceanbase:oceanbase-client` |\n\n"
                + "小众 JDBC 数据库请同时提供方言 SPI jar，并用 `--driver` 与 `--database-type` 显式指定。\n";
    }

    private static String propertyValue(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
