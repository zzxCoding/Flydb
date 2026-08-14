[English](./README.en.md) | 中文

# Flydb

Flydb 是面向任意支持 JDBC 驱动的数据库的 Schema 版本化迁移工具：内置主流数据库方言，以国产信创数据库支持为特色，并通过 `DatabaseType` SPI 扩展小众 JDBC 数据库。

项目采用 Java 8 基线。`flydb-core` 保持零第三方运行时依赖；独立 CLI 不捆绑数据库厂商驱动，可从 `drivers/`、运行时 classpath、Maven 本地仓库和 Maven settings 配置的企业私服自动解析后动态加载。

> Flydb 2.0 正在按 [实施计划](./docs/design/09-implementation-plan.md) 分阶段交付。当前代码已覆盖 core 命令、SQL 解析、历史仓储、锁与事务语义、主流与信创内置方言、独立 CLI，以及 Spring Boot 2/3 starter。数据库兼容状态以实际测试证据为准，不把方言实现等同于生产认证。

## 能做什么

- 版本化迁移 `V1__init.sql`、可重复迁移 `R__view.sql`、撤销迁移 `U1__init.sql`
- `migrate`、`info`、`validate`、`baseline`、`repair`、`clean`、`undo`
- advisory lock 或锁表互斥、DDL 事务差异、失败记录阻断与 repair 恢复
- UTF-8 Properties、环境变量、命令行参数、密码文件和占位符
- migrate/undo 的 `--dry-run`，只完成探测、校验、解析和打印，不执行 SQL
- 外置 JDBC 驱动和方言 SPI，适配不能公开分发驱动的信创数据库

## 数据库状态

| 数据库家族 | 内置方言 | 当前验证层级 |
|---|---:|---|
| MySQL 8 | 是 | 自动化兼容测试；CLI 发行包端到端验证 |
| PostgreSQL | 是 | 自动化兼容测试 |
| Oracle | 是 | 原生 Oracle 方言单测与授权实例契约入口；真实环境待补 |
| 达梦 DM8 | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| 人大金仓 KingbaseES | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| openGauss | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| OceanBase / TiDB | 复用对应家族 | 轻量兼容测试；真实环境覆盖持续补充 |
| 其他 JDBC 数据库 | 可扩展 | 需提供 JDBC 驱动及 `DatabaseType` SPI 方言实现 |

每个数据库的驱动、连接、权限和已知限制见[数据库上手指南](./docs/getting-started/README.md)。状态只代表当前验证证据，不代表厂商认证。

信创或新型 JDBC 数据库需要快速接入时，先看[JDBC 数据库快速接入](./docs/getting-started/jdbc-integration.md)：驱动 JAR 放置、`--driver`/`--database-type` 选择、MySQL/Oracle 家族复用，以及自定义 `DatabaseType` SPI 都有可复制示例。

## Agent Skills

### 给 Agent 的首屏入口

如果你是 Agent，请先阅读仓库根目录的 [`AGENTS.md`](./AGENTS.md)。涉及 Flydb CLI、JDBC 迁移或 Schema 变更时，按该文件安装/启用 `flydb-cli` 后再执行命令；文档-only 任务只需阅读相关文档即可。文件内还包含各主流 Agent 的发现目录、首次安全执行流程和 JDBC 接入边界。

### 给人类用户：复制给 Agent

如果你希望 Agent 自动安装并使用 Flydb Skill，可以直接复制下面这段话：

> 我正在使用 Flydb。请先阅读并遵循 [AGENTS.md](https://github.com/zzxCoding/Flydb/blob/main/AGENTS.md)，然后安装或启用 `flydb-cli` Skill。安装完成后先确认 `bin/flydb version`；涉及迁移时先执行 `validate` 和 `--dry-run migrate`。不要把密码写入命令、日志或 SQL；未经我明确授权，不要执行会修改数据库的命令。完成后告诉我 Skill 的安装位置和下一步。

仓库同时提供 [`flydb-skills`](./flydb-skills/README.md)，当前先包含 [`flydb-cli`](./flydb-skills/skills/flydb-cli/SKILL.md)。它使用开放的 `SKILL.md` 格式，面向 Claude Code、OpenAI Codex、Gemini CLI、Kimi Code、ZCode、Hermes Agent、Pi 等主流 Agent 复用；同时引用 CLI 命令、配置、错误码和 JDBC 接入文档，帮助 Agent 安全地执行 `init`、`validate`、`info`、`migrate`、`baseline`、`repair`、`undo` 和 `clean`。Skill 不复制 CLI 手册；修改 CLI 行为时以 `docs/` 为准并同步检查 Skill。

## 五分钟上手

前置条件：Java 8 或更高版本、一个已创建的目标数据库，以及与 Java 8 兼容的 JDBC 驱动。

```bash
unzip flydb-cli-2.0.0.zip
cd flydb-cli-2.0.0

# 示例：把 mysql-connector-j.jar 放入 drivers/
cp /path/to/mysql-connector-j.jar drivers/

bin/flydb init \
  --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user \
  --database-type mysql \
  --yes
```

`init` 会生成 `flydb.conf`、`db/migration/V1__init.sql` 和本项目专用的 `drivers/README.md`，并拒绝覆盖已有文件。编辑首个迁移脚本后执行：

```bash
export FLYDB_PASSWORD='replace-me'

bin/flydb --dry-run migrate
bin/flydb migrate
bin/flydb info
bin/flydb validate
```

密码也可以直接写入 `flydb.password=明文密码`（仅建议本地临时测试），或通过 `flydb.password=${env:DB_PASSWORD}`、`flydb.password.file=/run/secrets/db_password` 提供。生产和共享环境不要把明文密码提交到版本库。

## 脚本命名

```text
V1__create_user.sql       # 版本化迁移，只成功应用一次
V1.1__add_status.sql      # 点分版本号
R__refresh_user_view.sql  # checksum 变化后再次执行
U1__create_user.sql       # 撤销最近一次已应用的 V1
```

> **2.0 命名变更：** `R<版本>__...sql` 已被禁止并报 `FLYDB-2005`。回退脚本请使用 `U<版本>__...sql`；可重复迁移统一使用不带版本号的 `R__...sql`，不能通过配置关闭这项检查。

默认位置为 `filesystem:db/migration`，会递归扫描所有子目录；历史记录中的脚本名保留相对路径。`init` 生成的配置改用绝对位置，避免跨目录执行时受 CWD 影响。SQL 支持 `${key}` 占位符；命令行用 `-Dkey=value` 传入。未定义占位符会在执行前报错并指出脚本行号；业务运行时模板需要原样入库时设置 `flydb.placeholder-replacement=false`。

## 配置优先级

```text
CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值
```

配置文件查找顺序为 `--config` 指定文件、当前目录 `flydb.conf`、安装目录 `conf/flydb.conf`。未知的 `flydb.*` 配置键会直接报错并给出近似建议。

常用命令：

```bash
bin/flydb migrate
bin/flydb migrate --target-version 3
bin/flydb migrate --start-version 2 --end-version 5
bin/flydb migrate --version-source directory --target-version 20230531 \
  --migration-order directory-version
bin/flydb info --color=never
bin/flydb validate
bin/flydb baseline --baseline-version 5
bin/flydb repair
bin/flydb undo

# clean 默认禁用；非交互环境必须同时满足两道开关
bin/flydb clean --clean-disabled=false --force
```

默认 `--target-version` 仍精确匹配文件版本，起止范围包含边界。版本族、目录版本、路径 glob/regex 与目录版本排序是显式启用的高级规则；例如 `--version-source directory --target-version 20230531` 会选择目录版本 `20230531` 下的 `V20230531.1/.2/.3`。完整模式和安全约束见[配置项参考](./docs/reference/configuration.md#版本选择路径过滤与排序)。任何筛选都不会绕过校验或 `out-of-order` 保护。

文件版本以数字开头，并支持点、下划线或连字符分隔的字母数字 token，例如 `V20260327-b06.4__data.sql`。疑似版本化 SQL 但命名无法解析时会报 `FLYDB-2001`，不会静默跳过。`clean` 会输出对象统计与逐对象删除进度。

退出码：`0` 成功、`1` 一般错误、`2` 校验失败、`3` 锁冲突或超时、`4` 配置错误、`5` 用户中断。

完整配置、命令语义和错误码见 [配置与 CLI 设计](./docs/design/06-config-cli.md)；也可直接查阅[配置项参考](./docs/reference/configuration.md)和[错误码参考](./docs/reference/errors.md)；架构入口见 [设计总览](./docs/design/00-overview.md)。

## Java API

应用内使用时由调用方管理 `DataSource`，Flydb 不接管连接池生命周期：

```java
Flydb flydb = Flydb.configure()
    .dataSource(dataSource)
    .databaseType("mysql") // 兼容家族或自定义方言建议显式指定
    .locations("classpath:db/migration")
    // .targetVersion("3")
    // .startVersion("2").endVersion("5")
    .load();

flydb.migrate();
```

`flydb-core` 不依赖特定连接池、日志框架或 JDBC 驱动。独立 CLI 才负责 URL 配置和 `drivers/` 动态加载。

## Spring Boot

按应用技术栈选择一个 starter；引入后默认在 Spring 容器初始化期间执行 `migrate`，失败会中止应用启动：

```xml
<!-- Spring Boot 3.x / Java 17+ -->
<dependency>
  <groupId>com.flydb</groupId>
  <artifactId>flydb-spring-boot-3-starter</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

Java 8 存量应用改用 `flydb-spring-boot-2-starter`，对应 Spring Boot 2.7.18。[Spring 官方已说明](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/) 2.7.18 是 Boot 2.x 的最后一个开源支持版本，因此新项目应优先选择 Boot 3 starter；Boot 2 starter 的定位是服务暂时无法升级的存量系统。

默认复用应用主 `DataSource`：

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/demo
spring.datasource.username=app_user
spring.datasource.password=${DB_PASSWORD}

flydb.locations=classpath:db/migration
flydb.database-type=mysql
# flydb.target-version=3
# flydb.start-version=2
# flydb.end-version=5
```

需要权限隔离时，额外设置 `flydb.url/user/password`：Flydb 使用独立的 DDL 账号迁移，应用仍使用低权限主 `DataSource`。设置 `flydb.enabled=false` 可完全关闭自动装配。两个 starter 均生成 `flydb.*` IDE 配置元数据，并将 core 日志桥接到 SLF4J。

可运行工程见 [Boot 2 示例](./examples/boot2-demo) 和 [Boot 3 示例](./examples/boot3-demo)。自动装配与版本边界见 [Spring Boot Starter 设计](./docs/design/07-spring-boot-starter.md)。

## 从源码验证

完整 reactor（含 Boot 3）使用 Java 17 构建；如果终端已配置本仓库开发环境，可先执行 `jdk17`：

```bash
jdk17
mvn verify
```

Boot 2 starter、Boot 2 示例、core 与 CLI 仍保持 Java 8 字节码。CLI 构建产物位于 `flydb-cli/target/flydb-cli-2.0.0-SNAPSHOT.zip`。core 的 JaCoCo 行覆盖率门禁为 80%，并由 Maven Enforcer 保证零非测试运行时依赖。

阶段 8 发布前检查：

```bash
./scripts/check-bytecode.sh 52 \
  flydb-core/target/classes flydb-cli/target/classes \
  flydb-spring-boot-2-starter/target/classes examples/boot2-demo/target/classes
./scripts/check-bytecode.sh 61 \
  flydb-spring-boot-3-starter/target/classes examples/boot3-demo/target/classes
./mvnw -DskipTests deploy \
  -DaltDeploymentRepository=local::file:./target/staging
./scripts/check-release-artifacts.sh target/staging flydb-cli/target
```

本地集成契约默认只启动 MySQL 8；需要显式运行某个 CI 方言项时设置 `-Pmysql`/`-Ppostgresql` 与 `-Dflydb.integration.database=<dialect>`。完整矩阵由 `.github/workflows/ci.yml` 执行。

## 许可证

[Apache-2.0](./LICENSE)。Flydb 自身按 Apache License 2.0 发布；JDBC 驱动由使用者自行获取，并遵守各厂商的许可证与分发条款。发行包同时附带 [`NOTICE`](./NOTICE)。
