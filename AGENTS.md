# Flydb 仓库 Agent 指引

本文件适用于整个 Flydb 仓库，记录所有开发 Agent 都必须遵守的稳定约束。
模块专属规则应放在对应子目录的 `AGENTS.md`；命令参数、配置项和安装步骤应留在
各自的参考文档中，不在本文件重复维护。

## 1. 开始工作前

- 先阅读根目录 [`README.md`](README.md)，再按任务读取相关设计或参考文档。
- 涉及 Flydb CLI、`flydb.conf`、`drivers/`、JDBC 迁移或 Schema 变更时，必须先读
  [`flydb-cli/SKILL.md`](flydb-skills/skills/flydb-cli/SKILL.md)。只有当前 Agent
  必须安装 Skill 才能发现它时，才按 [`flydb-skills/README.md`](flydb-skills/README.md)
  操作；无法自动发现时直接读取仓库内的 `SKILL.md`。
- CLI 命令、配置、错误码和 `--json` 机器输出分别以 [`commands.md`](docs/reference/commands.md)、
  [`configuration.md`](docs/reference/configuration.md)、[`errors.md`](docs/reference/errors.md) 和
  [`json-output.md`](docs/reference/json-output.md) 为准。不要凭记忆重构选项、错误语义或输出 schema。
- 涉及 MCP Adapter（`flydb-skills/mcp/`、`mcp.json` 或 MCP tools）时，以
  [`mcp-tools.md`](docs/reference/mcp-tools.md) 和 [`11-plan-artifact.md`](docs/design/11-plan-artifact.md)
  为准；TypeScript 侧不得新增领域逻辑或第二套计划模型。
- 接入新 JDBC 数据库、厂商数据库或信创数据库前，先读
  [`jdbc-integration.md`](docs/getting-started/jdbc-integration.md) 及对应数据库指南。
- 纯文档任务只需读取本文件和相关源文档，不要求安装 Skill，也不要求连接数据库。

## 2. 仓库结构与版本边界

| 路径 | 职责 | Java 边界 |
|---|---|---|
| `flydb-core` | 公共 API、迁移引擎、方言 SPI | Java 8，零第三方运行时依赖 |
| `flydb-cli` | picocli CLI 与发行包 | Java 8 |
| `flydb-spring-boot-2-starter` | Spring Boot 2.7 自动装配 | Java 8 |
| `flydb-spring-boot-3-starter` | Spring Boot 3 自动装配 | Java 17 |
| `flydb-integration-tests` | Testcontainers 与数据库契约测试 | Java 17 |
| `examples` | Boot 2/3 可运行示例 | 跟随对应 starter |
| `docs/design` | 已确认的架构、领域与实现契约 | 不适用 |
| `docs/reference` | CLI、配置和错误码的事实来源 | 不适用 |
| `flydb-skills` | 版本匹配的 Agent Skill 与评测 | 不适用 |

完整模块关系和依赖边界见 [`01-modules.md`](docs/design/01-modules.md)。

## 3. 修改规则

- `docs/design` 中标记为“契约”或“决策”的内容不能静默偏离。设计与实现冲突时，
  先说明冲突及影响，再决定是修代码还是更新设计。
- 修改公共 API、CLI 行为、配置键、错误码、驱动加载或数据库支持范围时，必须同步
  更新对应测试和源文档；随后检查 `flydb-cli/SKILL.md` 是否仍保持薄引用而未复制手册。
- `flydb-core` 不得新增非 `test` 作用域依赖。core、CLI、Boot 2 starter 不得使用
  Java 9+ 语法或 API；Boot 3 专属代码不得下沉到 Java 8 模块。
- 优先在公共边界补回归测试，再修改实现。迁移发现、版本选择、状态推导等共享语义
  应复用既有领域对象，不能在 CLI、starter 或方言中另写一套规则。
- 不下载、提交或重新分发厂商 JDBC 驱动。测试依赖和真实数据库验证必须符合厂商
  许可证及当前环境授权。
- 不改动与当前任务无关的用户工作区变更，也不要为了通过检查删除或重写已有产物。

## 4. 构建与验证

完整 reactor 使用 JDK 17。在仓库根目录运行：

```bash
./mvnw -B verify
```

如果本机通过 shell 函数切换 JDK，可先执行 `jdk17`。定向验证优先使用：

```bash
./mvnw -B -pl <module> -am test
```

涉及 Java 8 模块或发布边界时，还要参考 `.github/workflows/ci.yml` 执行 Java 8
兼容构建和 `scripts/check-bytecode.sh`。`flydb-core` 的 JaCoCo 行覆盖率门禁为 80%。

集成测试可能启动 Docker 数据库；仅在任务需要且环境允许时运行。达梦、金仓、Oracle
等授权数据库没有真实实例证据时，只能报告本地或模拟验证，不能宣称产品兼容性已通过。
文档-only 修改至少运行 `git diff --check` 并核对链接和命令与源文档一致。

## 5. CLI 与数据库安全

1. 先确认使用的是源码仓库还是已构建发行包；数据库操作使用发行包中的 `bin/flydb`。
2. 执行数据库工作前先运行 `bin/flydb version`，再按 Skill 和参考文档选择命令。
3. 迁移写入前先执行 `validate` 和 `--dry-run migrate`，核对发现清单、范围与目标库。
4. `migrate`、`baseline`、`repair`、`undo`、`clean` 等数据库写操作必须得到用户明确授权。
   `clean` 默认保持禁用，只有用户明确确认破坏性范围后才能启用和执行。
5. 密码不得出现在命令参数、日志、Skill 输出或 SQL 文件中。共享和生产环境优先使用
   `FLYDB_PASSWORD`、`${env:VAR}` 或 `flydb.password.file`；明文配置仅限本地临时测试。
6. 汇报时写明 CLI 路径、脱敏后的数据库目标、实际命令、dry-run/写入状态、退出码和
   验证结果。未执行的真实数据库验证必须明确标注。

## 6. 新 JDBC 数据库

JDBC 驱动和 Flydb 方言是两个独立问题。不能仅凭 MySQL 或 Oracle 语法兼容就复用
数据库家族；还要核对历史表 DDL、标识符规则、DDL 事务、锁和脚本切分语义。语义不同时，
通过 `DatabaseType` SPI 提供独立实现，不要强制覆盖探测结果。

新数据库必须在获授权的实例上完成 `validate`、`--dry-run migrate` 和无害迁移验证后，
才能描述为已支持。Skill 格式兼容或方言单测通过，不等同于厂商认证或生产兼容证明。

## 7. 维护本文件

本文件只保留跨模块、稳定且高影响的仓库规则。Agent 安装矩阵归
`flydb-skills/README.md`，CLI 操作流程归 `SKILL.md`，参数和产品语义归
`docs/reference` 与 `docs/getting-started`。规则只适用于单个模块时，在该模块增加
更近层级的 `AGENTS.md`，不要继续加厚根文件。
