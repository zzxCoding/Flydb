# 配置项参考

本文面向已经决定接入 Flydb 的应用开发者和运维人员。CLI 使用 `flydb.conf`，Java API 使用 `FlydbConfiguration.Builder`，Spring Boot 使用 `flydb.*` 属性。三种入口最终汇入同一个 `flydb-core` 配置模型。

## 配置优先级

```text
CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值
```

配置文件按以下顺序查找：`--config` 指定文件、当前目录 `flydb.conf`、CLI 安装目录 `conf/flydb.conf`。未知键会报 `FLYDB-4001`，不会静默忽略。

内置方言标识包括 `mysql`、`postgresql`、`oracle`、`dm`、`kingbasees`、`opengauss`、`oceanbase` 和 `tidb`；小众 JDBC 数据库通过 `DatabaseType` SPI 扩展。

## CLI / 配置文件

| 配置键 | 环境变量 | CLI 选项 | 默认值 | 说明 |
|---|---|---|---|---|
| `flydb.url` | `FLYDB_URL` | `-u, --url` | 无 | JDBC URL，CLI 必填 |
| `flydb.user` | `FLYDB_USER` | `--user` | 无 | 数据库用户 |
| `flydb.password` | `FLYDB_PASSWORD` | `-p, --password` | 无 | 支持直接配置明文密码；仅本地临时测试建议使用，生产推荐环境变量或密码文件 |
| `flydb.driver` | `FLYDB_DRIVER` | `--driver` | 自动推断 | JDBC Driver 类名 |
| `flydb.driver-coordinate` | `FLYDB_DRIVER_COORDINATE` | `--driver-coordinate` | 内置数据库自动推断 | Maven 坐标 `groupId:artifactId:version`；小众或私服驱动显式填写 |
| `flydb.driver-download` | `FLYDB_DRIVER_DOWNLOAD` | `--driver-download` | `auto` | `auto` 按 Maven 有效仓库下载，`never` 禁止下载 |
| `flydb.driver-cache` | `FLYDB_DRIVER_CACHE` | `--driver-cache` | `~/.flydb/drivers` | 自动下载驱动的本地缓存目录 |
| `flydb.maven-settings` | `FLYDB_MAVEN_SETTINGS` | `--maven-settings` | `~/.m2/settings.xml` | Maven settings 文件；读取私服、镜像、认证、代理、Profile 仓库和本地仓库 |
| `flydb.maven-local-repository` | `FLYDB_MAVEN_LOCAL_REPOSITORY` | `--maven-local-repository` | Maven 有效本地仓库 | 显式覆盖 Maven 本地仓库目录 |
| `flydb.offline` | `FLYDB_OFFLINE` | `--offline` | `false` | 禁止所有远程驱动解析，仍使用本地来源 |
| `flydb.database-type` | `FLYDB_DATABASE_TYPE` | `--database-type` | 自动探测 | 方言标识；探测有歧义时显式指定 |
| `flydb.locations` | `FLYDB_LOCATIONS` | `-l, --locations` | `filesystem:db/migration` | 逗号分隔；每个位置递归扫描子目录；`filesystem:` 支持相对或绝对路径，相对路径以执行 CLI 的当前工作目录为基准；API 默认 `classpath:db/migration` |
| `flydb.encoding` | `FLYDB_ENCODING` | `--encoding` | `UTF-8` | SQL 文件编码 |
| `flydb.table` | `FLYDB_TABLE` | `--table` | `flydb_schema_history` | 历史表名 |
| `flydb.baseline-version` | `FLYDB_BASELINE_VERSION` | `--baseline-version` | `1` | baseline 版本 |
| `flydb.baseline-on-migrate` | `FLYDB_BASELINE_ON_MIGRATE` | `--baseline-on-migrate` | `false` | 存量非空库首次接入 |
| `flydb.validate-on-migrate` | `FLYDB_VALIDATE_ON_MIGRATE` | `--validate-on-migrate` | `true` | migrate 前校验 |
| `flydb.out-of-order` | `FLYDB_OUT_OF_ORDER` | `--out-of-order` | `false` | 是否允许补执行低版本迁移 |
| `flydb.target-version` | `FLYDB_TARGET_VERSION` | `--target-version` | 无 | 目标版本；默认精确匹配文件版本 |
| `flydb.start-version` | `FLYDB_START_VERSION` | `--start-version` | 无 | 执行范围起始版本，包含边界 |
| `flydb.end-version` | `FLYDB_END_VERSION` | `--end-version` | 无 | 执行范围结束版本，包含边界但不含该版本的 `.N` 子版本（如 `20260625` 不含 `20260625.3`）；需包含时用 `family-range`，命中时 `migrate` 会输出警告 |
| `flydb.version-selection` | `FLYDB_VERSION_SELECTION` | `--version-selection` | 自动推断 | `exact`、`range`、`family`、`family-range`、`regex` |
| `flydb.version-source` | `FLYDB_VERSION_SOURCE` | `--version-source` | `file` | 从文件名或相对目录读取筛选版本：`file\|directory` |
| `flydb.version-regex` | `FLYDB_VERSION_REGEX` | `--version-regex` | 无 | `regex` 模式的版本整串匹配表达式 |
| `flydb.directory-glob` | `FLYDB_DIRECTORY_GLOB` | `--directory-glob` | 无 | 相对父目录 glob |
| `flydb.file-glob` | `FLYDB_FILE_GLOB` | `--file-glob` | 无 | 文件名 glob |
| `flydb.path-glob` | `FLYDB_PATH_GLOB` | `--path-glob` | 无 | 完整相对路径 glob |
| `flydb.directory-regex` | `FLYDB_DIRECTORY_REGEX` | `--directory-regex` | 无 | 相对父目录整串正则 |
| `flydb.file-regex` | `FLYDB_FILE_REGEX` | `--file-regex` | 无 | 文件名整串正则 |
| `flydb.path-regex` | `FLYDB_PATH_REGEX` | `--path-regex` | 无 | 完整相对路径整串正则 |
| `flydb.migration-order` | `FLYDB_MIGRATION_ORDER` | `--migration-order` | `version` | `version\|directory-version` |
| `flydb.directory-version-regex` | `FLYDB_DIRECTORY_VERSION_REGEX` | `--directory-version-regex` | 最近的点分数字目录 | 提取 `version` 命名组或第一个捕获组 |
| `flydb.placeholders.<key>` | `FLYDB_PLACEHOLDERS_<KEY>` | `-D<key>=<value>` | 空 | SQL 占位符 |
| `flydb.placeholder-replacement` | `FLYDB_PLACEHOLDER_REPLACEMENT` | `--placeholder-replacement` | `true` | 是否执行 SQL 占位符替换；`false` 时 `${...}` 原样保留 |
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
| `flydb.batch-size` | `FLYDB_BATCH_SIZE` | `--batch-size` | `1` | SQL 语句 JDBC 批大小；`1`（默认）逐条执行并精确定位失败语句，`>1` 时按批提交减少远程库往返。远程库大批量 INSERT 可显著提速；MySQL 建议同时在 `flydb.url` 追加 `rewriteBatchedStatements=true` 才能获得改写合并收益。失败时语句序号按批内已执行计数推算，定位粒度略降 |

密码支持直接写入 `flydb.password=明文密码`，也支持 `${env:DB_PASSWORD}` 间接引用或 `flydb.password.file=/run/secrets/db_password`。明文配置会随文件备份、版本控制和权限错误而暴露，因此仅建议本地临时测试；生产和共享环境推荐环境变量或密码文件。Flydb 不会主动把密码写入日志、错误消息或 dry-run 输出。

### JDBC 驱动自动解析

CLI 依次检查安装目录 `drivers/*.jar`、运行时 classpath、Maven 本地仓库、Flydb 驱动缓存，最后按 Maven 有效配置访问远程仓库。Maven 本地仓库优先读取显式配置和 `maven.repo.local`，其次读取 `settings.xml` 的 `localRepository`，默认使用 `~/.m2/repository`。

远程解析遵循 `settings.xml` 的 `mirrors`、`activeProfiles`/`activeByDefault` Profile 中的 `repositories`、`servers` 基本认证和活动 `proxy`。如果配置了 `mirrorOf=*`，Central 会被镜像替换，Flydb 不会绕过企业私服直连公网。下载写入独立的 `~/.flydb/drivers` 缓存，不修改 Maven 本地仓库。

当前轻量解析器下载坐标对应的主驱动 JAR，不解析 Maven 传递依赖；需要额外伴随 JAR 的厂商驱动应将这些 JAR 一并放入 `drivers/`。Maven 加密密码当前也不会由 Flydb 解密；这类环境可通过受控环境变量插值提供凭据，或预先把驱动发布到本地仓库/`drivers/`。这些限制不会影响已验证的 MySQL Connector/J 常规 JDBC 连接入口，但使用驱动附加模块前应核对厂商依赖说明。

内置固定坐标覆盖 MySQL、PostgreSQL、Oracle 和 OceanBase。达梦、KingbaseES、openGauss 以及小众数据库的实际版本通常由厂商交付或企业私服决定，需配置完整坐标，例如：

```properties
flydb.driver=com.vendor.jdbc.Driver
flydb.driver-coordinate=com.company.jdbc:vendor-driver:3.2.1
```

迁移位置会递归扫描所有子目录，历史表中的 `script` 使用相对位置路径，例如 `tenant/a/V2__tenant.sql`。

### 迁移位置与已有历史

`validate` 会严格检查历史表中已应用的脚本仍能从当前 locations 发现。更换脚本目录后，如果旧位置不再可见，已应用记录会显示为 `MISSING` 并报 `FLYDB-2003`。迁移目录切换期间应把新旧位置逗号并列，例如：

```properties
flydb.locations=filesystem:/opt/app/new-migrations,filesystem:/opt/flydb/db/migration
```

`filesystem:` 相对路径始终以执行 CLI 时的当前工作目录为基准。`flydb init` 生成的 `flydb.conf` 会写入所创建 `db/migration` 目录的绝对路径，避免之后从其他目录执行时发生位置漂移；手工配置相对路径时需要自行保持固定 CWD。

### 版本选择、路径过滤与排序

版本必须以数字开头，可包含字母数字 token，并用点、下划线或连字符分隔，例如 `1.2`、`20260327-b06.4`。数字 token 按数值排序，字母 token 按不区分大小写的字典序排序；语义等价的版本（如 `1` 与 `1.0`）仍会按重复版本阻断。扫描到以 `V`/`U` 开头且以 `.sql` 结尾、但无法解析为版本化迁移的候选文件时，Flydb 报 `FLYDB-2001`，不会静默跳过。

不配置 `flydb.version-selection` 时，根据输入保持原有语义：存在 `target-version` 就使用 `exact`，存在起止边界就使用 `range`，均未配置则不筛选版本。显式模式的参数约束如下：

| 模式 | 必需参数 | 语义 |
|---|---|---|
| `exact` | `target-version` | 精确匹配一个版本 |
| `range` | `start-version`/`end-version` 至少一个 | 数值范围，包含边界，但不含结束版本的族子版本（`20260625.3` 相对 `20260625`）；命中会输出警告 |
| `family` | `target-version` | 目标及其子版本；`20230531-b06.4` 属于 `20230531`，`202305310.1` 不属于 |
| `family-range` | `start-version`/`end-version` 至少一个 | 版本族范围，结束族的子版本也包含 |
| `regex` | `version-regex` | 对规范化版本文本整串匹配 |

`version-source=file` 使用文件名里的版本。`version-source=directory` 使用相对父目录提取的版本，因此下面的精确目标会一次选择三个脚本：

```text
db/migration/20230531/
├── V20230531.1__schema.sql
├── V20230531.2__data.sql
└── V20230531.3__index.sql
```

```properties
flydb.version-source=directory
flydb.target-version=20230531
flydb.migration-order=directory-version
```

默认目录提取器寻找离文件最近的纯数字或点分数字目录。自定义目录形如 `release-20230531` 时，可在 Properties 中使用双反斜线：

```properties
flydb.directory-version-regex=(?:^|/)release-(?<version>\\d{8})(?=$|/)
```

选择目录版本或 `directory-version` 排序时，每个版本化文件都必须能提取目录版本，并且文件版本必须属于该目录版本族；不一致会报 `FLYDB-2001`，不会静默排序。默认 `version` 按文件版本的数字/字母自然顺序排序；`directory-version` 按目录版本、文件版本、规范化相对路径排序，两种规则都不依赖文件系统或 JAR entry 返回顺序。

路径 glob 支持 `*`、`?` 和跨目录的 `**`。glob 与 regex 分别匹配父目录、文件名或完整相对路径；相对路径统一使用 `/`。同一维度的 glob 与 regex 互斥，不同维度取交集。例如：

```properties
flydb.directory-glob=mysql/param/**
flydb.file-glob=V*__*.sql
# 高级用法；不要与同维度 directory-glob 同时配置
# flydb.directory-regex=^mysql/(param|trans)/\\d{8}$
```

路径过滤属于本地迁移发现规则，会影响所有读取本地迁移的命令；版本选择只作用于 `migrate` 和 `--dry-run migrate`。显式版本选择排除 `R__...sql`，并继续遵守校验、失败记录和 `out-of-order` 规则。

### 占位符替换

默认 `flydb.placeholder-replacement=true`，脚本中的 `${key}` 会在词法解析前替换，未定义时报 `FLYDB-2009`。如果脚本要把 `${workDate}`、`${taskDate}` 等表达式作为业务系统的运行时 SQL 模板原样写入数据库，设置：

```properties
flydb.placeholder-replacement=false
```

关闭后，真实 `migrate` 与 `--dry-run migrate` 都跳过 Flydb 占位符替换；`placeholder-prefix`、`placeholder-suffix` 和 `flydb.placeholders.*` 在该次执行中不生效。

## Spring Boot

Starter 默认复用应用主 `DataSource`。需要权限隔离时设置 `flydb.url`、`flydb.user`、`flydb.password`，Flydb 会创建独立迁移连接；应用连接池仍只承担业务访问。

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/demo
spring.datasource.username=app_user
spring.datasource.password=${DB_PASSWORD}

flydb.locations=classpath:db/migration
flydb.database-type=mysql
# flydb.placeholder-replacement=false
# flydb.url=jdbc:mysql://127.0.0.1:3306/demo
# flydb.user=flydb_ddl
# flydb.password=${FLYDB_DDL_PASSWORD}
```

`flydb.enabled=false` 会完全关闭自动装配。Boot 2 starter 面向 Java 8 存量应用；Boot 3 starter 要求 Java 17。

## 命名与安全边界

```text
V1__create_user.sql       版本化迁移
V20260327-b06.4__data.sql 含字母/连字符 token 的版本化迁移
R__refresh_user_view.sql  可重复迁移
U1__create_user.sql       撤销 V1
```

0.2 起 `R` 不带版本号。扫描到 `R1__...sql` 会报 `FLYDB-2005` 并阻断，不提供兼容开关；回退脚本请使用 `U<version>__...sql`。

`clean` 默认禁用；非交互执行还必须同时设置 `flydb.clean-disabled=false` 和 `--force`。执行时会输出 schema、对象总数、逐对象删除进度、历史表/锁表清理及完成日志。不要把真实密码提交到版本库。
