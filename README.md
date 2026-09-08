[English](./README.en.md) | 中文

<p align="center">
  <img src="./docs/assets/flydb-mascot-banner.png" alt="Flydb 数据飞行兽" width="520">
</p>

# Flydb

**数据库迁移，让人看得清，也让 Agent 接得上。**

[![CI](https://github.com/zzxCoding/Flydb/actions/workflows/ci.yml/badge.svg)](https://github.com/zzxCoding/Flydb/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/zzxCoding/Flydb)](https://github.com/zzxCoding/Flydb/releases/latest)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](./LICENSE)
![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)

[官网与演示](https://flydb.zzxcoding.dev) · [下载](https://github.com/zzxCoding/Flydb/releases/latest) · [GUI 上手](./docs/getting-started/web.md) · [Agent 接入](./flydb-skills/README.md) · [文档](./docs/getting-started/README.md)

Flydb 是给开发、运维和实施人员使用的数据库版本迁移工具。用本机 GUI 管理多套配置、核对 SQL 和查看执行进度；用 CLI 接入脚本与 CI；有 Agent 时，一键复制上下文继续处理。三种入口使用同一份配置与迁移引擎，GUI 和同机 CLI 共用执行记录。

支持 MySQL、PostgreSQL、Oracle 及多种国产数据库；**Java 8+ 即可运行，GUI 无需 Node.js、外网或大模型**。

![Flydb 本机工作台：多环境分组、迁移状态与 Agent 交接](./docs/assets/flydb-workbench.png)
*实际工作台界面，使用虚构的演示配置与迁移记录。*

## 选择适合你的用法

| 使用场景 | Flydb 怎么帮你 |
|---|---|
| 管理多套开发、测试和生产配置 | 按分组折叠、拖拽整理；表单与高级文件编辑直接维护原配置 |
| 实施升级前核对变更 | 先预览 SQL；大量版本可搜索、分页，大 SQL 可全文查找与完整下载 |
| 跟踪执行、排查失败 | 查看脚本进度、事务结果与执行后核验；保留记录，不把未知结果当成成功 |
| 让 Agent 接着处理 | “复制给 Agent”整理脱敏配置、迁移状态和最近错误；也可通过 Skill、JSON 与 MCP 接入 |
| 在应用或流水线里自动迁移 | CLI 适配 CI，Java API 与 Spring Boot 2/3 starter 复用同一迁移引擎 |

## 一条命令打开 GUI

从 [Releases](https://github.com/zzxCoding/Flydb/releases/latest) 下载 ZIP，解压后运行：

```bash
cd flydb-cli-0.3.7
bin/flydb web
```

Windows 使用 `bin\flydb.bat web`。浏览器中导入已有 `flydb.conf`，或新建配置；添加对应 JDBC 驱动后即可连接数据库。无需注册账号，支持中文 / English、明暗主题。启动工作台不会执行迁移。

已有配置也可以直接打开：

```bash
bin/flydb --config /path/to/project/flydb.conf web
```

详见 [GUI 指南](./docs/getting-started/web.md)。驱动由使用者按厂商许可提供，不随 ZIP 捆绑。

<details>
<summary><strong>更习惯终端？从 CLI 开始</strong></summary>

以下以已创建的 MySQL 数据库为例：

```bash
cp /path/to/mysql-connector-j.jar drivers/
bin/flydb init --url 'jdbc:mysql://127.0.0.1:3306/demo' --user flydb_user --database-type mysql --yes
export FLYDB_PASSWORD='replace-me'
bin/flydb validate
bin/flydb --dry-run migrate
# 核对 SQL 与目标后执行
bin/flydb migrate
bin/flydb info
```

`init` 生成 `flydb.conf`、`db/migration/V1__init.sql` 和 `drivers/README.md`，拒绝覆盖已有文件。V1 示例为 `SELECT 1;`，请按实际变更替换；配置也支持环境变量与密码文件。

</details>

## 迁移能力，贯穿三种入口

- **先核对，再执行**：checksum 校验、并发锁、事务处理与失败阻断；预览后配置或脚本发生变化时重新核验。
- **适应现有部署环境**：Java 8 零第三方运行时依赖内核，独立 CLI 发行包，以及 Spring Boot 2/3 starter。
- **覆盖主流与国产数据库**：内置方言与驱动加载机制，支持 `DatabaseType` SPI 扩展；各数据库的验证层级见下表。
- **人和 Agent 共用事实**：结构化 JSON、Plan Artifact 与执行记录；未知或中断的迁移不会自动重放。GUI 高级操作中的 clean 需风险确认和输入 `CLEAN`。

Flydb 管理迁移流程与数据库方言行为，不会把任意厂商 SQL 自动翻译成其他数据库语法。存在语法差异时，请按数据库家族维护迁移目录。

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
- [x] **开发体验与机器契约**：`--json` 机器输出、protocolVersion 契约版本化、CI 接入文档、Agent Plugins 1.0 插件包（`v0.3.0`；包管理器与 Docker 镜像按需启动）
- [x] **Agent 分发**：MCP 适配（TypeScript Adapter + 九个领域工具，写入默认不注册）与 Plan Artifact v1 计划摘要；CLI ZIP 内附已构建 Adapter
- [x] **本机图形工作台**：多配置、分组、迁移预览、执行记录与 Agent 上下文交接
- [ ] **存量变更智能**：影响分析、应用引用扫描、覆盖率与未知项标注
- [ ] **Agent 安全变更运行时**：Plan → Validate → Risk → Approval → Apply → Verify 协议

路线图代表方向而非交付承诺，详细说明与产品边界见 [ROADMAP.md](./ROADMAP.md)。

## Agent 使用

存量结构与代码分析可直接使用独立的 [`flydb-analysis` Skill](flydb-skills/docs/flydb-analysis.md)：提供方言预检、Schema 快照与漂移、对象依赖、应用引用和变更影响分析的基础工作流。当前为 preview，支持离线材料与未使用 Flydb 的项目，Skill 可独立于 JAR 更新。

Agent 请先阅读仓库根目录的 [`AGENTS.md`](./AGENTS.md)，按其指引安装或启用 [`flydb-cli` Skill](./flydb-skills/skills/flydb-cli/SKILL.md) 后再执行命令；涉及迁移时先执行 `validate` 和 `--dry-run migrate`。Skill 是薄编排层，不复制 CLI 手册；命令、配置和错误码细节以 [`docs/reference`](./docs/reference/README.md) 为准，Skill 面向 Claude Code、Codex、Gemini CLI、ZCode 等主流 Agent 复用，格式与安装方式见 [`flydb-skills`](./flydb-skills/README.md)。宿主支持 MCP 时，可通过 [`mcp.json`](./flydb-skills/mcp.json) 以 MCP tools 调用 Flydb（写入工具默认不注册），见 [MCP 工具参考](./docs/reference/mcp-tools.md)与[接入指南](./docs/getting-started/mcp-adapter.md)。

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
  <version>0.3.7</version>
</dependency>
```

Spring Boot 应用选择对应 starter，容器初始化期间执行 `migrate`，失败会中止应用启动：

```xml
<!-- Spring Boot 3.x / Java 17+ -->
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-spring-boot-3-starter</artifactId>
  <version>0.3.7</version>
</dependency>
<!-- Spring Boot 2.7 / Java 8 -->
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-spring-boot-2-starter</artifactId>
  <version>0.3.7</version>
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

完整 reactor（含 Boot 3）使用 Java 17 和 Node.js 22.12+ 构建（npm 随 Node 提供）；Boot 2 starter、Boot 2 示例、core 与 CLI 保持 Java 8 字节码。如果终端通过 shell 函数切换 JDK，可先执行 `jdk17`：

```bash
./mvnw verify
```

CLI 构建产物位于 `flydb-cli/target/flydb-cli-0.3.7.zip`。core 的 JaCoCo 行覆盖率门禁为 80%，并由 Maven Enforcer 保证零非测试运行时依赖。

数据库集成契约默认跳过；显式设置 `-Pmysql`/`-Ppostgresql` 与 `-Dflydb.integration.database=<dialect>` 后，才会启动临时数据库执行对应测试。完整矩阵由 `.github/workflows/ci.yml` 执行。

<details>
<summary>发布前检查（阶段 8）</summary>

```bash
./scripts/check-bytecode.sh 52 \
  flydb-core/target/classes flydb-runtime/target/classes flydb-web/target/classes flydb-cli/target/classes \
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
