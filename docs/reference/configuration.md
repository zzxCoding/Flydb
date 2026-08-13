# 配置项参考

本文面向已经决定接入 Flydb 的应用开发者和运维人员。CLI 使用 `flydb.conf`，Java API 使用 `FlydbConfiguration.Builder`，Spring Boot 使用 `flydb.*` 属性。三种入口最终汇入同一个 `flydb-core` 配置模型。

## 配置优先级

```text
CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值
```

配置文件按以下顺序查找：`--config` 指定文件、当前目录 `flydb.conf`、CLI 安装目录 `conf/flydb.conf`。未知键会报 `FLYDB-4001`，不会静默忽略。

## CLI / 配置文件

| 配置键 | 环境变量 | CLI 选项 | 默认值 | 说明 |
|---|---|---|---|---|
| `flydb.url` | `FLYDB_URL` | `-u, --url` | 无 | JDBC URL，CLI 必填 |
| `flydb.user` | `FLYDB_USER` | `--user` | 无 | 数据库用户 |
| `flydb.password` | `FLYDB_PASSWORD` | `-p, --password` | 无 | 推荐用环境变量或密码文件 |
| `flydb.driver` | `FLYDB_DRIVER` | `--driver` | 自动推断 | JDBC Driver 类名 |
| `flydb.database-type` | `FLYDB_DATABASE_TYPE` | `--database-type` | 自动探测 | 方言标识；探测有歧义时显式指定 |
| `flydb.locations` | `FLYDB_LOCATIONS` | `-l, --locations` | `filesystem:db/migration` | 逗号分隔；API 默认 `classpath:db/migration` |
| `flydb.encoding` | `FLYDB_ENCODING` | `--encoding` | `UTF-8` | SQL 文件编码 |
| `flydb.table` | `FLYDB_TABLE` | `--table` | `flydb_schema_history` | 历史表名 |
| `flydb.baseline-version` | `FLYDB_BASELINE_VERSION` | `--baseline-version` | `1` | baseline 版本 |
| `flydb.baseline-on-migrate` | `FLYDB_BASELINE_ON_MIGRATE` | `--baseline-on-migrate` | `false` | 存量非空库首次接入 |
| `flydb.validate-on-migrate` | `FLYDB_VALIDATE_ON_MIGRATE` | `--validate-on-migrate` | `true` | migrate 前校验 |
| `flydb.out-of-order` | `FLYDB_OUT_OF_ORDER` | `--out-of-order` | `false` | 是否允许补执行低版本迁移 |
| `flydb.placeholders.<key>` | `FLYDB_PLACEHOLDERS_<KEY>` | `-D<key>=<value>` | 空 | SQL 占位符 |
| `flydb.placeholder-prefix` | `FLYDB_PLACEHOLDER_PREFIX` | `--placeholder-prefix` | `${` | 占位符前缀 |
| `flydb.placeholder-suffix` | `FLYDB_PLACEHOLDER_SUFFIX` | `--placeholder-suffix` | `}` | 占位符后缀 |
| `flydb.sql-migration-prefix` | `FLYDB_SQL_MIGRATION_PREFIX` | `--sql-migration-prefix` | `V` | 版本化脚本前缀 |
| `flydb.repeatable-migration-prefix` | `FLYDB_REPEATABLE_MIGRATION_PREFIX` | `--repeatable-migration-prefix` | `R` | 可重复脚本前缀 |
| `flydb.undo-migration-prefix` | `FLYDB_UNDO_MIGRATION_PREFIX` | `--undo-migration-prefix` | `U` | 撤销脚本前缀 |
| `flydb.sql-migration-separator` | `FLYDB_SQL_MIGRATION_SEPARATOR` | `--sql-migration-separator` | `__` | 版本与描述分隔符 |
| `flydb.sql-migration-suffix` | `FLYDB_SQL_MIGRATION_SUFFIX` | `--sql-migration-suffix` | `.sql` | 脚本后缀 |
| `flydb.callbacks` | `FLYDB_CALLBACKS` | `--callbacks` | 空 | Java Callback 类名，逗号分隔 |
| `flydb.clean-disabled` | `FLYDB_CLEAN_DISABLED` | `--clean-disabled` | `true` | clean 防呆开关 |
| `flydb.lock-timeout-seconds` | `FLYDB_LOCK_TIMEOUT_SECONDS` | `--lock-timeout-seconds` | `60` | 获取迁移锁的等待秒数 |

密码可使用 `${env:DB_PASSWORD}` 间接引用或 `flydb.password.file=/run/secrets/db_password`。密码不会写入日志、错误消息或 dry-run 输出。

## Spring Boot

Starter 默认复用应用主 `DataSource`。需要权限隔离时设置 `flydb.url`、`flydb.user`、`flydb.password`，Flydb 会创建独立迁移连接；应用连接池仍只承担业务访问。

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/demo
spring.datasource.username=app_user
spring.datasource.password=${DB_PASSWORD}

flydb.locations=classpath:db/migration
flydb.database-type=mysql
# flydb.url=jdbc:mysql://127.0.0.1:3306/demo
# flydb.user=flydb_ddl
# flydb.password=${FLYDB_DDL_PASSWORD}
```

`flydb.enabled=false` 会完全关闭自动装配。Boot 2 starter 面向 Java 8 存量应用；Boot 3 starter 要求 Java 17。

## 命名与安全边界

```text
V1__create_user.sql       版本化迁移
R__refresh_user_view.sql  可重复迁移
U1__create_user.sql       撤销 V1
```

2.0 起 `R` 不带版本号。扫描到 `R1__...sql` 会报 `FLYDB-2005` 并阻断，不提供兼容开关；回退脚本请使用 `U<version>__...sql`。

`clean` 默认禁用；非交互执行还必须同时设置 `flydb.clean-disabled=false` 和 `--force`。不要把真实密码提交到版本库。
