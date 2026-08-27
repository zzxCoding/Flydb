# Changelog

Flydb 的重要变更记录在本文件中。版本遵循语义化版本；正式发行包与发布说明见
[GitHub Releases](https://github.com/zzxCoding/Flydb/releases)。

## [Unreleased]

### 文档

- 长时间 CLI 迁移推荐用 `nohup` 与前台 Agent/终端会话解耦，同时持久化日志、PID 和退出码完成标记；Agent Skill 同步要求工具超时时保留现有迁移，待进程真正结束后再执行 `info`/`validate`。

## [0.3.2] - 2026-08-26

### 修复

- 修复带前导表头注释的纯 DML 脚本被误判为非事务脚本：事务判定现在会线性跳过任意数量的前导空白、`--`、`/* ... */`，MySQL 家族同时识别 `#` 注释，再对第一个可执行 token 应用 `INSERT`/`UPDATE`/`DELETE`/`MERGE` 严格允许列表。
- 判定过程不改写实际交给 JDBC 的 SQL，保留注释、脚本行号和错误定位；只有注释而没有 DML 的输入不会被误判为事务化数据脚本。

### 验证边界

- 回归测试从公开 `MigrateCommand` 契约覆盖多行 `--` 表头注释、块注释、失败整体回滚及历史无痕。真实 OceanBase 吞吐仍需在获授权实例上用 0.3.2 发行包复验。

## [0.3.1] - 2026-08-26

### 修复

- MySQL/Oracle 家族在脚本仅包含 `INSERT`、`UPDATE`、`DELETE`、`MERGE` 时，改为整份脚本与成功历史记录同事务、末尾一次提交。DDL、PL/SQL、显式事务控制、`WITH` 和未知语句继续保守使用非事务路径，避免动态 SQL 或隐式提交破坏原有失败语义。
- 纯 DML 迁移失败或 JDBC 连接中断时整体回滚且不写 `success=false`，连接恢复后可由历史记录安全判断是否重跑；Flydb 不在迁移内部自动重连或自动重放，避免提交结果不确定时重复写入。
- 逐条 SQL 执行失败时保留原始 `SQLException` cause，使 `--debug` 可继续追踪 SQLState、厂商异常类型与网络断线错误链。
- 固化 OceanBase-Oracle 的 schema 内锁表语义：同一租户、不同当前 schema 使用各自的 `flydb_schema_lock`，可并行迁移；同一 schema 仍全程互斥。不会回退到实例级、仅含历史表名的 `DBMS_LOCK` 锁键。

### 验证边界

- 回归测试覆盖非事务 DDL 方言上的四类 DML、失败整体回滚、安全重跑、混合 DDL 原语义和 JDBC 原始异常链。发布流水线继续执行 Java 8/17、全 reactor、MCP Adapter、字节码、签名与发行包门禁。
- 本次未在仓库内保存 OceanBase 凭据或厂商驱动；真实 OceanBase 吞吐与断线恢复需在获授权实例上复验，不能由单元测试替代。

## [0.3.0] - 2026-08-26

路线图阶段三：Agent 分发（MCP 适配 + Plan Artifact v1）。Java 8 字节码与依赖边界不变；Node 只进入可选的 MCP 分发层，普通 CLI/starter 用户不需要安装。

### 新增

- MCP Adapter（`flydb-skills/mcp/`，npm 包名 `flydb-mcp`，独立 SemVer `0.1.0`）：TypeScript + 官方 MCP SDK，stdio 对外提供 9 个领域工具——默认注册 5 个只读工具（`flydb_version`/`flydb_info`/`flydb_validate`/`flydb_plan_migrate`/`flydb_plan_undo`），写入工具（`flydb_migrate`/`flydb_baseline`/`flydb_repair`/`flydb_undo`）默认不注册、由操作者设置 `FLYDB_MCP_ENABLE_WRITES=true` 显式开启（fail closed）。每次调用以无 shell 子进程执行 `bin/flydb --json`，校验信封（`protocolVersion=1`、退出码一致）后映射到 MCP `structuredContent`/`content`/`isError`；CLI 缺失、超时、取消、stdout 非法等进程层失败返回 `FLYDB_MCP-xxxx` Adapter 诊断，不伪造领域错误码。永不暴露 `clean`/`init`/`execute_sql`；数据库工具只接收绝对 `workingDirectory`+`configPath` 白名单字段并固定追加 `--driver-download never`；启动时执行 `--json version` 握手（要求 CLI ≥ 0.3.0）。事实来源为 `docs/reference/mcp-tools.md` 与 `docs/getting-started/mcp-adapter.md`。
- `flydb-skills/mcp.json`：Agent Plugins 1.0.0 MCP 配置（stdio，入口 `${PLUGIN_ROOT}/mcp/dist/server.mjs`），插件宿主可直接装载；`dist` 为单文件 bundle，宿主无需 `npm install`。
- Plan Artifact v1（`docs/design/11-plan-artifact.md` 契约）：`--json --dry-run migrate|undo` 信封新增 `plan` 摘要对象（`algorithm: flydb-plan-v1`、`direction`、`id`、`targetVersion`、`migrationCount`、`statementCount`）与 `migrations[]` 标识字段（`version`、`description`、`checksum`、`statementCount`）。`plan.id` 是规范文本的 SHA-256，确定性语义覆盖脚本集合、顺序、checksum、语句切分及占位符替换后的逐条实际 SQL，任一变化都会产生不同 id；文本 dry-run（包括 `--quiet`）同步打印计划标识。实现位于 `flydb-core` `PlanArtifact`（零第三方依赖），`protocolVersion` 保持 `1`（追加字段）。

### 验证

- TypeScript 单元测试 58 例（信封校验、脱敏、CLI 定位、CliRunner 真实子进程、工具白名单、结果映射、server 行为、插件结构）。
- 跨运行时端到端：真实 `flydb-cli` 发行包 + 官方 MCP client 驱动 `initialize`/`tools/list`/`tools/call`（含版本握手拒绝、写入工具默认不注册与开启态注册）。
- 写入路径一次性测试库验证（Docker PostgreSQL 16）：默认态写入工具不可见且直接调用返回未知工具错误；开启态完成 `flydb_plan_migrate` → `flydb_migrate` → `flydb_info` → `flydb_validate` 闭环，`flydb_baseline`/`flydb_repair`/`flydb_undo` 各自 fixture 通过。未在生产或授权共享库上执行。

### 阶段二：开发体验与机器契约

路线图阶段二：开发体验与机器契约。让任何程序（CI、IDE、外部宿主、Agent）都能稳定消费 Flydb CLI 的结果，为阶段三 MCP 适配打地基。命令语义、锁行为与退出码不变。

### 新增

- `--json` 全局选项：所有命令在 stdout 输出恰好一行紧凑 JSON 信封（`protocolVersion`、`command`、`status`、`exitCode` + 命令载荷），stderr 仅保留人类诊断；错误信封携带 `error.code`（Flydb 错误码）、脱敏 `detail` 与校验 `problems` 清单。`--json` 模式零交互：密码、`clean`、`init` 的交互提示一律按非交互规则报错。设计契约为 `docs/design/10-machine-contract.md`，schema 参考为 `docs/reference/json-output.md`。
- `protocolVersion`（当前 `1`）契约版本化承诺：同一版本内只新增字段、消费者必须忽略未知字段；契约覆盖命令集、全局选项、配置键、错误码、退出码与 JSON schema。
- CI 接入文档（`docs/getting-started/ci-integration.md`）：GitHub Actions 与 Jenkins 官方示例——secrets 注入 `FLYDB_PASSWORD`、`--json` + `jq` 提取、退出码/错误码分流（锁冲突重试、校验失败阻断）、dry-run 制品留档与审批门。
- `flydb-skills` 成为合法的 Agent Plugins 1.0.0 插件包：根目录新增 `plugin.json`（`name: flydb`，版本随发布对齐），支持该规范的宿主（ChatGPT、Codex、Cursor、Copilot、VS Code 等）可直接装载；`mcp.json` 留待阶段三。

### 变更

- CLI 默认以 UTF-8 包装 `System.out`/`System.err`：JDK 18+ 行为不变（JEP 400）；旧 JDK 且控制台为 GBK 等本地编码时，中文文本输出由平台编码改为 UTF-8。机器输出契约要求 UTF-8。
- 密码/URL 凭据脱敏逻辑移至 `com.flydb.cli.output.SecretRedactor`（行为不变），文本表格、dry-run 与 JSON 输出共用。

### 修复

- OceanBase 4.2.1.2 Oracle 模式不再调用不可用的 `DBMS_LOCK.REQUEST/RELEASE`，回落 Oracle 家族锁表行锁；`DbmsLockMigrationLock` 保留为未启用的可选实现。

## [0.2.1] - 2026-08-15

### 变更

- Maven 坐标 groupId 由 `com.flydb` 调整为 `io.github.zzxcoding`（Sonatype Central Portal 经 GitHub 验证的 namespace）；Java 包名保持 `com.flydb.*` 不变。`0.2.0` 及更早版本未发布到任何 Maven 仓库，无迁移影响。

### 新增

- Maven Central 发布链路：根 POM 新增 `central` 发布 profile（GPG 签名 + central-publishing-maven-plugin），tag 触发的 release workflow（`.github/workflows/release.yml`）一次完成全量 verify、签名、发布 Central 与 GitHub Release 附件；发布范围为 parent、core、CLI（jar）、两个 starter，examples 与 integration-tests 不发布。本版本即首个经该链路发布到 Maven Central 的版本。
- 兼容性矩阵参考文档（`docs/reference/compatibility.md`）：模块与 Java/Spring Boot 运行环境、数据库方言与驱动、验证层级。
- `scripts/check-release-artifacts.sh` 新增 `--signatures` 模式，发布前校验每个产物均有 GPG 签名且验证通过。

## [0.2.0] - 2026-08-14

首个正式公开版本，提供可用于本地开发、CI 和应用启动阶段的 Schema 迁移运行时。

### 新增

- Java 8 零第三方运行时依赖的迁移内核，以及 Java 8 CLI。
- `migrate`、`info`、`validate`、`baseline`、`repair`、`undo`、`clean` 和 `version` 命令。
- MySQL、PostgreSQL、Oracle、达梦 DM8、KingbaseES、openGauss、OceanBase、TiDB 方言或兼容家族。
- Spring Boot 2.7 与 Spring Boot 3 starter。
- 递归迁移发现、精确/范围/版本族选择、目录版本、glob/regex 路径过滤。
- 外置 JDBC 驱动、Maven 私服解析、离线模式及 `DatabaseType` SPI。
- 发行包内置版本匹配的文档、`AGENTS.md` 与 `flydb-cli` Agent Skill。

### 安全与可靠性

- 并发迁移锁、DDL 事务差异、失败记录阻断与恢复。
- checksum 校验、`migrate`/`undo` dry-run、稳定退出码与错误码。
- `clean` 默认禁用并要求双重确认；密码支持环境变量和密码文件。

### 当前边界

- GitHub Release 提供 CLI ZIP；Maven Central 和包管理器分发尚未开放。
- Flydb 不自动把任意厂商 SQL 转换为其他数据库语法。
- 达梦、KingbaseES、openGauss 的公开证据为方言和驱动元数据契约测试，真实环境认证仍待补充。

[0.3.2]: https://github.com/zzxCoding/Flydb/releases/tag/v0.3.2
[0.3.1]: https://github.com/zzxCoding/Flydb/releases/tag/v0.3.1
[0.3.0]: https://github.com/zzxCoding/Flydb/releases/tag/v0.3.0
[0.2.1]: https://github.com/zzxCoding/Flydb/releases/tag/v0.2.1
[0.2.0]: https://github.com/zzxCoding/Flydb/releases/tag/v0.2.0
