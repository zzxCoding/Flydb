[English](./README.en.md) | 中文

<p align="center">
  <img src="./docs/assets/flydb-mascot-banner.png" alt="Flydb 数据飞行兽吉祥物" width="100%">
</p>

# Flydb

[![CI](https://github.com/zzxCoding/Flydb/actions/workflows/ci.yml/badge.svg)](https://github.com/zzxCoding/Flydb/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/zzxCoding/Flydb)](https://github.com/zzxCoding/Flydb/releases/latest)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](./LICENSE)
![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)
[![LINUX DO](https://img.shields.io/badge/LINUX-DO-FFB003.svg?logo=data:image/svg%2bxml;base64,DQo8c3ZnIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiPjxwYXRoIGQ9Ik00Ni44Mi0uMDU1aDYuMjVxMjMuOTY5IDIuMDYyIDM4IDIxLjQyNmM1LjI1OCA3LjY3NiA4LjIxNSAxNi4xNTYgOC44NzUgMjUuNDV2Ni4yNXEtMi4wNjQgMjMuOTY4LTIxLjQzIDM4LTExLjUxMiA3Ljg4NS0yNS40NDUgOC44NzRoLTYuMjVxLTIzLjk3LTIuMDY0LTM4LjAwNC0yMS40M1EuOTcxIDY3LjA1Ni0uMDU0IDUzLjE4di02LjQ3M0MxLjM2MiAzMC43ODEgOC41MDMgMTguMTQ4IDIxLjM3IDguODE3IDI5LjA0NyAzLjU2MiAzNy41MjcuNjA0IDQ2LjgyMS0uMDU2IiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZWNlY2VjO2ZpbGwtb3BhY2l0eToxIi8+PHBhdGggZD0iTTQ3LjI2NiAyLjk1N3EyMi41My0uNjUgMzcuNzc3IDE1LjczOGE0OS43IDQ5LjcgMCAwIDEgNi44NjcgMTAuMTU3cS00MS45NjQuMjIyLTgzLjkzIDAgOS43NS0xOC42MTYgMzAuMDI0LTI0LjM4N2E2MSA2MSAwIDAgMSA5LjI2Mi0xLjUwOCIgc3R5bGU9InN0cm9rZTpub25lO2ZpbGwtcnVsZTpldmVub2RkO2ZpbGw6IzE5MTkxOTtmaWxsLW9wYWNpdHk6MSIvPjxwYXRoIGQ9Ik03Ljk4IDcwLjkyNmMyNy45NzctLjAzNSA1NS45NTQgMCA4My45My4xMTNRODMuNDI2IDg3LjQ3MyA2Ni4xMyA5NC4wODZxLTE4LjgxIDYuNTQ0LTM2LjgzMi0xLjg5OC0xNC4yMDMtNy4wOS0yMS4zMTctMjEuMjYyIiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZjlhZjAwO2ZpbGwtb3BhY2l0eToxIi8+PC9zdmc+)](https://linux.do)

Flydb 是面向任意支持 JDBC 驱动的数据库的 Schema 版本化迁移工具：内置主流数据库方言，以国产信创数据库支持为特色，并通过 `DatabaseType` SPI 扩展小众 JDBC 数据库。

**现在**，Flydb 0.2 是一个可靠的迁移运行时：`migrate`、`info`、`validate`、`baseline`、`repair`、`undo`、`clean` 等命令，配合并发锁、事务语义、checksum 校验、失败阻断与恢复，内置主流与信创共 8 个数据库方言，并提供 Spring Boot 2/3 starter。**长期方向**，是让人类与 AI Agent 共用同一套安全的数据库变更能力：Agent 决定“改什么”，Flydb 保证“怎么改是安全的”。各阶段目标与当前进度见[路线图](./ROADMAP.md)。

> **能力边界：** Flydb 负责迁移版本、执行安全与数据库方言适配，不会把任意一套厂商 SQL 自动转换成所有数据库语法。存在方言差异时，请按数据库家族维护迁移目录，具体组织方式见[多环境自动化指南](./docs/getting-started/multi-environment.md#4-脚本仓库按数据库家族分目录)。

## 为什么选 Flydb

- **信创数据库一等公民**：达梦 DM8、人大金仓 KingbaseES、openGauss、OceanBase、TiDB 与 MySQL、PostgreSQL、Oracle 同为内置方言；CLI 不捆绑厂商驱动，从 `drivers/`、运行时 classpath 或 Maven 私服外置解析加载，适配不能公开分发的驱动。
- **零依赖的 Java 8 内核**：`flydb-core` 无任何第三方运行时依赖（由 Maven Enforcer 强制），可直接进入任何存量 Java 8 系统；Boot 3 / Java 17 环境使用独立 starter。
- **对人和 Agent 同样友好**：稳定退出码与错误码、`--dry-run` 预览、非交互可用；发行包随附与 CLI 版本匹配的 Agent Skill 和文档。
- **安全默认**：`clean` 默认禁用且需双重开关；失败迁移阻断后续执行；密码支持环境变量与密码文件，不落命令行、日志和 SQL。

## 快速上手

前置条件：Java 8 或更高版本、一个已创建的目标数据库，以及与 Java 8 兼容的 JDBC 驱动。

```bash
curl -LO https://github.com/zzxCoding/Flydb/releases/download/v0.2.1/flydb-cli-0.2.1.zip
unzip flydb-cli-0.2.1.zip
cd flydb-cli-0.2.1

# 示例：把 mysql-connector-j.jar 放入 drivers/
cp /path/to/mysql-connector-j.jar drivers/

bin/flydb init \
  --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user \
  --database-type mysql \
  --yes

export FLYDB_PASSWORD='replace-me'
bin/flydb --dry-run migrate
bin/flydb migrate
bin/flydb info
bin/flydb validate
```

`init` 会生成 `flydb.conf`、`db/migration/V1__init.sql` 和 `drivers/README.md`，并拒绝覆盖已有文件。密码也可通过 `flydb.password=${env:DB_PASSWORD}` 或 `flydb.password.file=/run/secrets/db_password` 提供；明文写入 `flydb.password` 仅建议本地临时测试。

## 数据库支持

| 数据库家族 | 内置方言 | 当前验证层级 |
|---|---:|---|
| MySQL  | 是 | 自动化兼容测试；CLI 发行包端到端验证 |
| PostgreSQL | 是 | 自动化兼容测试 |
| Oracle | 是 | 自动化契约测试；已在授权真实实例完成 validate、clean、migrate 端到端验证 |
| 达梦 DM8 | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| 人大金仓 KingbaseES | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| openGauss | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| OceanBase | 复用 Oracle/MySQL 家族 | Oracle 租户已在授权真实实例完成端到端验证；MySQL 租户为轻量兼容测试 |
| TiDB | 复用 MySQL 家族 | 轻量兼容测试；真实环境覆盖持续补充 |
| 其他 JDBC 数据库 | 可扩展 | 需提供 JDBC 驱动及 `DatabaseType` SPI 方言实现 |

每个数据库的驱动、连接、权限和已知限制见[数据库上手指南](./docs/getting-started/README.md)。状态只代表当前验证证据，不代表厂商认证。模块、Java/Spring Boot 版本与数据库驱动的完整兼容矩阵见[兼容性矩阵](./docs/reference/compatibility.md)。信创或新型 JDBC 数据库快速接入见 [JDBC 数据库快速接入](./docs/getting-started/jdbc-integration.md)。

## 路线图

- [x] **可靠的迁移运行时**：迁移引擎、8 个内置方言、CLI、Spring Boot starter、Agent Skill、`v0.2.0` GitHub Release、`v0.2.1` Maven Central 发布
- [ ] **开发体验与机器契约**：`--json` 机器输出、protocolVersion 契约版本化、CI 接入文档（包管理器与 Docker 镜像按需启动）
- [ ] **Agent 分发**：基于稳定 CLI 契约的 MCP 适配
- [ ] **存量变更智能**：影响分析、应用引用扫描、覆盖率与未知项标注
- [ ] **Agent 安全变更运行时**：Plan → Validate → Risk → Approval → Apply → Verify 协议

路线图代表方向而非交付承诺，详细说明与产品边界见 [ROADMAP.md](./ROADMAP.md)。

## Agent 使用

Agent 请先阅读仓库根目录的 [`AGENTS.md`](./AGENTS.md)，按其指引安装或启用 [`flydb-cli` Skill](./flydb-skills/skills/flydb-cli/SKILL.md) 后再执行命令；涉及迁移时先执行 `validate` 和 `--dry-run migrate`。Skill 是薄编排层，不复制 CLI 手册；命令、配置和错误码细节以 [`docs/reference`](./docs/reference/README.md) 为准，Skill 面向 Claude Code、Codex、Gemini CLI、ZCode 等主流 Agent 复用，格式与安装方式见 [`flydb-skills`](./flydb-skills/README.md)。

CLI 发行 ZIP 同时包含 `AGENTS.md`、`docs/` 和 `flydb-skills/`，因此只有发行包、没有源码 checkout 时，也能使用与当前 CLI 版本匹配的文档和 Skill；复制 Skill 到 Agent 目录后，应保留发行包路径供其查找这些文档。

<details>
<summary>给人类用户：让 Agent 自动安装并使用 Flydb Skill</summary>

> 我正在使用 Flydb。请先阅读并遵循 [AGENTS.md](https://github.com/zzxCoding/Flydb/blob/main/AGENTS.md)，然后安装或启用 `flydb-cli` Skill。安装完成后先确认 `bin/flydb version`；涉及迁移时先执行 `validate` 和 `--dry-run migrate`。不要把密码写入命令、日志或 SQL；未经我明确授权，不要执行会修改数据库的命令。完成后告诉我 Skill 的安装位置和下一步。

</details>

## 在应用中使用

Java API——`flydb-core` 不依赖特定连接池、日志框架或 JDBC 驱动，由调用方管理 `DataSource`：

```java
Flydb flydb = Flydb.configure()
    .dataSource(dataSource)
    .databaseType("mysql") // 兼容家族或自定义方言建议显式指定
    .locations("classpath:db/migration")
    // .targetVersion("3")
    .load();

flydb.migrate();
```

纯 Java 应用引入 `flydb-core`：

```xml
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-core</artifactId>
  <version>0.2.1</version>
</dependency>
```

Spring Boot 应用选择对应 starter，容器初始化期间执行 `migrate`，失败会中止应用启动：

```xml
<!-- Spring Boot 3.x / Java 17+ -->
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-spring-boot-3-starter</artifactId>
  <version>0.2.1</version>
</dependency>
<!-- Spring Boot 2.7 / Java 8 -->
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-spring-boot-2-starter</artifactId>
  <version>0.2.1</version>
</dependency>
```

> CLI 已通过 [GitHub Release](https://github.com/zzxCoding/Flydb/releases) 分发；`v0.2.1` 起坐标 `io.github.zzxcoding` 的各模块已发布到 Maven Central（Java 包名保持 `com.flydb.*` 不变），更早版本需从源码构建。

Java 8 存量应用改用 `flydb-spring-boot-2-starter`（Boot 2.7.18；[Spring 官方已说明](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/) 2.7.18 是 Boot 2.x 最后一个开源支持版本，因此新项目应优先 Boot 3 starter）。默认复用应用主 `DataSource`；需要权限隔离时设置 `flydb.url/user/password`，用独立 DDL 账号迁移；`flydb.enabled=false` 可完全关闭自动装配。可运行示例：[Boot 2 示例](./examples/boot2-demo)、[Boot 3 示例](./examples/boot3-demo)，详见 [Spring Boot Starter 设计](./docs/design/07-spring-boot-starter.md)。

## 命名与配置

```text
V1__create_user.sql       # 版本化迁移，只成功应用一次
V1.1__add_status.sql      # 点分版本号
R__refresh_user_view.sql  # checksum 变化后再次执行
U1__create_user.sql       # 撤销最近一次已应用的 V1
```

> **命名变更：** `R<版本>__...sql` 已被禁止并报 `FLYDB-2005`，不能通过配置关闭。回退脚本请使用 `U<版本>__...sql`；可重复迁移统一使用不带版本号的 `R__...sql`。

- 默认位置 `filesystem:db/migration`，递归扫描所有子目录；`init` 生成的配置使用绝对位置，避免受 CWD 影响。
- 配置优先级 `CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值`；配置文件按 `--config` 指定、当前目录、安装目录 `conf/` 的顺序查找；未知的 `flydb.*` 键直接报错并给出近似建议。
- SQL 支持 `${key}` 占位符，命令行用 `-Dkey=value` 传入；未定义占位符在执行前报错并指出脚本行号。
- 退出码：`0` 成功、`1` 一般错误、`2` 校验失败、`3` 锁冲突或超时、`4` 配置错误、`5` 用户中断。

```bash
bin/flydb migrate --target-version 3
bin/flydb migrate --start-version 2 --end-version 5
bin/flydb validate
bin/flydb baseline --baseline-version 5
bin/flydb repair
bin/flydb undo
bin/flydb clean --clean-disabled=false --force   # clean 默认禁用；非交互环境需双开关
```

版本族、目录版本、路径 glob/regex 过滤与目录版本排序是显式启用的高级规则，任何筛选都不会绕过校验或 `out-of-order` 保护；完整模式与安全约束见[配置项参考](./docs/reference/configuration.md#版本选择路径过滤与排序)。命令语义见[命令参考](./docs/reference/commands.md)，错误码见[错误码参考](./docs/reference/errors.md)。多数据库、多套测试与生产环境的自动化组织方式见[多环境自动化指南](./docs/getting-started/multi-environment.md)。

## 从源码构建

完整 reactor（含 Boot 3）使用 Java 17 构建；Boot 2 starter、Boot 2 示例、core 与 CLI 保持 Java 8 字节码。如果终端通过 shell 函数切换 JDK，可先执行 `jdk17`：

```bash
./mvnw verify
```

CLI 构建产物位于 `flydb-cli/target/flydb-cli-0.2.1.zip`。core 的 JaCoCo 行覆盖率门禁为 80%，并由 Maven Enforcer 保证零非测试运行时依赖。

本地集成契约默认只启动 MySQL 8；需要显式运行某个 CI 方言项时设置 `-Pmysql`/`-Ppostgresql` 与 `-Dflydb.integration.database=<dialect>`，完整矩阵由 `.github/workflows/ci.yml` 执行。

<details>
<summary>发布前检查（阶段 8）</summary>

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

</details>

## 参与贡献

欢迎通过 Issue 和 PR 参与贡献。完整流程见[贡献指南](./CONTRIBUTING.md)；提交前请运行 `./mvnw -B verify` 保证测试与覆盖率门禁通过。安全问题请按[安全策略](./SECURITY.md)私下报告，不要在公开 Issue 中披露漏洞细节。架构与设计文档入口见[设计总览](./docs/design/00-overview.md)。

## 许可证

[Apache-2.0](./LICENSE)。Flydb 自身按 Apache License 2.0 发布；JDBC 驱动由使用者自行获取，并遵守各厂商的许可证与分发条款。发行包同时附带 [`NOTICE`](./NOTICE)。
