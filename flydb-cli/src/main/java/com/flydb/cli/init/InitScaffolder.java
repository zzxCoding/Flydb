package com.flydb.cli.init;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 生成可直接编辑的 CLI 初始目录，并且永不覆盖已有文件。 */
public final class InitScaffolder {

    public List<Path> create(Path directory, String url, String user, String databaseType) {
        return create(directory, url, user, null, databaseType);
    }

    /**
     * 生成脚手架并持久化显式驱动类名。
     *
     * <p>旧重载保留给已有调用方；CLI 的 {@code init --driver} 使用此重载，
     * 避免厂商 URL 无法自动推断时初始化完成后还要手工补配置。
     */
    public List<Path> create(Path directory, String url, String user,
                             String driver, String databaseType) {
        Path configuration = directory.resolve("flydb.conf");
        Path migration = directory.resolve("db/migration/V1__init.sql");
        Path driverReadme = directory.resolve("drivers/README.md");
        for (Path file : Arrays.asList(configuration, migration)) {
            if (Files.exists(file)) {
                throw new FlydbException(ErrorCode.INIT_TARGET_EXISTS,
                        "目标文件已存在，拒绝覆盖: " + file);
            }
        }
        try {
            Files.createDirectories(migration.getParent());
            Files.createDirectories(driverReadme.getParent());
            writeNew(configuration, configuration(url, user, driver, databaseType,
                    migration.getParent().toAbsolutePath().normalize()));
            writeNew(migration, migration());
            List<Path> files = new ArrayList<Path>();
            files.add(configuration);
            files.add(migration);
            if (!Files.exists(driverReadme)) {
                try {
                    writeNew(driverReadme, driverReadme());
                    files.add(driverReadme);
                } catch (FileAlreadyExistsException ignored) {
                    // A concurrent init won the race; preserve its driver guide.
                }
            }
            return Collections.unmodifiableList(files);
        } catch (FileAlreadyExistsException e) {
            String file = e.getFile() == null ? "目标文件" : e.getFile();
            throw new FlydbException(ErrorCode.INIT_TARGET_EXISTS,
                    "目标文件已存在，拒绝覆盖: " + file, e);
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "生成 init 脚手架失败: " + e.getMessage(), e);
        }
    }

    private static void writeNew(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String configuration(String url, String user, String driver,
                                        String databaseType, Path migrationDirectory) {
        StringBuilder value = new StringBuilder()
                .append("# Flydb 0.2 配置（UTF-8 Properties）\n")
                .append("# 配置优先级：CLI 参数 > FLYDB_* 环境变量 > 本文件 > 内置默认值。\n")
                .append("# 修改本文件后，可执行 bin/flydb validate 检查配置与迁移脚本。\n\n")
                .append("# JDBC 连接地址（必填）；CLI: -u/--url；环境变量: FLYDB_URL。\n")
                .append("# 建议填写具体数据库名/服务名，不要把密码写进 URL。\n")
                .append("# 示例：jdbc:mysql://127.0.0.1:3306/demo\n")
                .append("flydb.url=").append(propertyValue(url)).append("\n\n")
                .append("# 数据库用户名；CLI: --user；环境变量: FLYDB_USER；如果数据库使用其他认证方式，可留空。\n");
        if (user != null && !user.isEmpty()) {
            value.append("flydb.user=").append(propertyValue(user)).append("\n\n");
        } else {
            value.append("# flydb.user=数据库用户名\n\n");
        }

        value.append("# 数据库密码；CLI: -p/--password；环境变量: FLYDB_PASSWORD。以下方式任选一种，不要同时维护多个来源。\n")
                .append("# 1) 本地临时测试可直接填写明文（不要提交到版本库）：\n")
                .append("# flydb.password=你的明文密码\n")
                .append("# 2) 推荐通过环境变量间接引用：\n")
                .append("# flydb.password=${env:DB_PASSWORD}\n")
                .append("# 3) 容器/Kubernetes Secret 文件：\n")
                .append("# flydb.password.file=/run/secrets/db_password\n\n")

                .append("# JDBC Driver 类名；CLI: --driver；环境变量: FLYDB_DRIVER。通常按 URL 自动推断，厂商 URL 无法推断时填写。\n");
        if (driver != null && !driver.isEmpty()) {
            value.append("flydb.driver=").append(propertyValue(driver)).append("\n\n");
        } else {
            value.append("# flydb.driver=com.vendor.jdbc.Driver\n\n");
        }

        value.append("# 驱动自动解析顺序：drivers/、classpath、Maven 本地仓库、~/.flydb/drivers、Maven 有效私服/镜像。\n")
                .append("# MySQL/PostgreSQL/Oracle/OceanBase 有内置固定坐标；小众数据库填写完整坐标。\n")
                .append("# flydb.driver-coordinate=com.vendor:vendor-jdbc:1.2.3\n")
                .append("# 默认读取 ~/.m2/settings.xml，并遵循 mirror、active profile repository、server 认证和 proxy。\n")
                .append("# flydb.maven.settings=/opt/company/maven/settings.xml\n")
                .append("# flydb.maven-local-repository=/data/maven/repository\n")
                .append("# auto 允许从有效 Maven 仓库下载；never 禁止下载。\n")
                .append("flydb.driver-download=auto\n")
                .append("# offline=true 禁止联网，但仍检查所有本地来源。\n")
                .append("flydb.offline=false\n")
                .append("# 下载缓存默认 ~/.flydb/drivers。\n")
                .append("# flydb.driver-cache=/data/flydb/drivers\n\n");

        value.append("# 方言标识；CLI: --database-type；环境变量: FLYDB_DATABASE_TYPE。\n")
                .append("# 留空时自动探测；兼容数据库建议显式填写 mysql/oracle 等家族名。\n");
        if (databaseType != null && !databaseType.isEmpty()) {
            value.append("flydb.database-type=").append(propertyValue(databaseType)).append("\n\n");
        } else {
            value.append("# flydb.database-type=mysql\n\n");
        }

        return value.append("# 迁移脚本位置；CLI: -l/--locations；环境变量: FLYDB_LOCATIONS。\n")
                .append("# init 写入绝对路径，跨目录执行 bin/flydb 时不会随当前工作目录漂移；可用逗号分隔多个位置。\n")
                .append("flydb.locations=filesystem:")
                .append(propertyValue(migrationDirectory.toString())).append("\n\n")
                .append("# SQL 文件字符集；CLI: --encoding；环境变量: FLYDB_ENCODING；脚本含中文时保持 UTF-8。\n")
                .append("flydb.encoding=UTF-8\n\n")
                .append("# Schema 历史表名称；CLI: --table；环境变量: FLYDB_TABLE。\n")
                .append("# Flydb 用它记录版本、checksum、执行状态和锁信息。\n")
                .append("flydb.table=flydb_schema_history\n\n")
                .append("# baseline 写入的版本号；CLI: --baseline-version；环境变量: FLYDB_BASELINE_VERSION。\n")
                .append("# 仅执行 baseline 时使用，默认从 1 开始。\n")
                .append("flydb.baseline-version=1\n\n")
                .append("# 非空库首次 migrate 是否自动写入 baseline；CLI: --baseline-on-migrate；环境变量: FLYDB_BASELINE_ON_MIGRATE。\n")
                .append("# 默认 false，更安全。\n")
                .append("flydb.baseline-on-migrate=false\n\n")
                .append("# migrate 前是否校验脚本、失败记录和 checksum；CLI: --validate-on-migrate；环境变量: FLYDB_VALIDATE_ON_MIGRATE。\n")
                .append("# 生产建议保持 true。\n")
                .append("flydb.validate-on-migrate=true\n\n")
                .append("# 是否允许补执行低于当前最高版本的迁移；CLI: --out-of-order；环境变量: FLYDB_OUT_OF_ORDER。\n")
                .append("# 默认 false，避免意外乱序。\n")
                .append("flydb.out-of-order=false\n\n")
                .append("# 版本选择只作用于 migrate/--dry-run migrate；显式选择时不执行 R__...sql。\n")
                .append("# 默认精确匹配文件版本；CLI: --target-version；环境变量: FLYDB_TARGET_VERSION。\n")
                .append("# flydb.target-version=3\n")
                .append("# 包含边界的版本范围；可只设置其中一端；CLI: --start-version/--end-version。\n")
                .append("# flydb.start-version=2\n")
                .append("# flydb.end-version=5\n")
                .append("# 高级模式：exact|range|family|family-range|regex；省略时由上述参数推断。\n")
                .append("# flydb.version-selection=family\n")
                .append("# 版本来源：file（默认）或 directory；目录版本可整体选择目录内的 .1/.2/.3。\n")
                .append("# flydb.version-source=directory\n")
                .append("# flydb.version-regex=^2023(05|07)\\\\d{2}(\\\\.\\\\d+)?$\n\n")
                .append("# 路径过滤基于以 / 分隔的相对路径；不同维度取交集，同维度 glob/regex 互斥。\n")
                .append("# flydb.directory-glob=mysql/param/**\n")
                .append("# flydb.file-glob=V*__*.sql\n")
                .append("# flydb.path-glob=mysql/**/V*.sql\n")
                .append("# flydb.directory-regex=^mysql/(param|trans)/\\\\d{8}$\n")
                .append("# flydb.file-regex=^V2023\\\\d{4}\\\\.\\\\d+__.*\\\\.sql$\n")
                .append("# flydb.path-regex=^mysql/.*/V.*\\\\.sql$\n\n")
                .append("# 排序：version（默认）或 directory-version；目录模式校验文件版本属于目录版本族。\n")
                .append("# flydb.migration-order=directory-version\n")
                .append("# 默认提取最近的数字目录；Properties 自定义正则中的反斜线需要写两次。\n")
                .append("# flydb.directory-version-regex=(?:^|/)(?<version>\\\\d+(?:\\\\.\\\\d+)*)(?=$|/)\n\n")
                .append("# SQL 占位符示例；CLI: -Dschema=app；环境变量: FLYDB_PLACEHOLDERS_SCHEMA。\n")
                .append("# 脚本中的 ${schema} 将替换为配置值。\n")
                .append("# flydb.placeholders.schema=app\n\n")
                .append("# 是否替换占位符；CLI: --placeholder-replacement；环境变量: FLYDB_PLACEHOLDER_REPLACEMENT。\n")
                .append("# 脚本需要把 ${...} 作为业务运行时模板原样入库时设置 false。\n")
                .append("flydb.placeholder-replacement=true\n\n")
                .append("# 占位符前缀；CLI: --placeholder-prefix；环境变量: FLYDB_PLACEHOLDER_PREFIX；默认识别 ${key}。\n")
                .append("flydb.placeholder-prefix=${\n")
                .append("# 占位符后缀；CLI: --placeholder-suffix；环境变量: FLYDB_PLACEHOLDER_SUFFIX；默认识别 ${key}。\n")
                .append("flydb.placeholder-suffix=}\n\n")
                .append("# 脚本命名示例：V1__init.sql、R__view.sql、U1__init.sql。\n")
                .append("# 版本化迁移前缀；CLI: --sql-migration-prefix；环境变量: FLYDB_SQL_MIGRATION_PREFIX；默认 V。\n")
                .append("flydb.sql-migration-prefix=V\n")
                .append("# 可重复迁移前缀；CLI: --repeatable-migration-prefix；环境变量: FLYDB_REPEATABLE_MIGRATION_PREFIX；默认 R。\n")
                .append("flydb.repeatable-migration-prefix=R\n")
                .append("# 撤销迁移前缀；CLI: --undo-migration-prefix；环境变量: FLYDB_UNDO_MIGRATION_PREFIX；默认 U。\n")
                .append("flydb.undo-migration-prefix=U\n")
                .append("# 版本与描述的分隔符；CLI: --sql-migration-separator；环境变量: FLYDB_SQL_MIGRATION_SEPARATOR；默认 __。\n")
                .append("flydb.sql-migration-separator=__\n")
                .append("# SQL 脚本文件后缀；CLI: --sql-migration-suffix；环境变量: FLYDB_SQL_MIGRATION_SUFFIX；默认 .sql。\n")
                .append("flydb.sql-migration-suffix=.sql\n\n")
                .append("# 可选 Callback 类名；CLI: --callbacks；环境变量: FLYDB_CALLBACKS。\n")
                .append("# 多个类用逗号分隔，且每个类必须实现 Callback 接口。\n")
                .append("# flydb.callbacks=com.example.FlydbCallback\n\n")
                .append("# clean 安全开关；CLI: --clean-disabled；环境变量: FLYDB_CLEAN_DISABLED。\n")
                .append("# true 时禁止清空 schema，除非显式关闭并二次确认。\n")
                .append("flydb.clean-disabled=true\n\n")
                .append("# 获取迁移锁的最长等待时间（秒）；CLI: --lock-timeout-seconds；环境变量: FLYDB_LOCK_TIMEOUT_SECONDS。\n")
                .append("# 并发迁移时超时返回 FLYDB-3001。\n")
                .append("flydb.lock-timeout-seconds=60\n").toString();
    }

    private static String migration() {
        return "-- Flydb 首个版本化迁移。请替换为项目所需的 DDL。\n"
                + "SELECT 1;\n";
    }

    private static String driverReadme() {
        return "# JDBC 驱动解析说明\n\n"
                + "Flydb 不捆绑 JDBC 驱动。CLI 会先检查本目录和 Maven 本地仓库；找不到时遵循 "
                + "`~/.m2/settings.xml` 的私服、镜像、认证和代理下载到 `~/.flydb/drivers`。"
                + "完全离线时配置 `flydb.offline=true`；也可以继续把 Java 8 兼容驱动 jar 放在本目录。\n\n"
                + "| 数据库 | 驱动类 / 获取线索 |\n|---|---|\n"
                + "| MySQL / TiDB | `com.mysql.cj.jdbc.Driver`，Maven `com.mysql:mysql-connector-j` |\n"
                + "| PostgreSQL | `org.postgresql.Driver`，Maven `org.postgresql:postgresql` |\n"
                + "| Oracle | `oracle.jdbc.OracleDriver`，从 Oracle 制品库获取 `ojdbc` |\n"
                + "| openGauss | `org.opengauss.Driver`，Maven `org.opengauss:opengauss-jdbc` |\n"
                + "| KingbaseES | `com.kingbase8.Driver`，从人大金仓交付介质或内部制品库获取 |\n"
                + "| 达梦 DM8 | `dm.jdbc.driver.DmDriver`，从达梦安装目录或内部制品库获取 |\n"
                + "| OceanBase | `com.oceanbase.jdbc.Driver`，Maven `com.oceanbase:oceanbase-client` |\n\n"
                + "厂商 URL 未被内置映射识别，或需要复用 MySQL/Oracle 家族时，请用 `--driver <驱动类>` "
                + "与 `--database-type <方言名>` 显式指定；小众数据库还需把实现 `DatabaseType` SPI 的方言 jar 放在本目录。\n";
    }

    private static String propertyValue(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
