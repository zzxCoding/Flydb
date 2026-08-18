# 10 机器契约：--json 输出与 protocolVersion

> [← 09 实施交接计划](09-implementation-plan.md) | [返回总览](00-overview.md)

**本文档是契约**：标记为契约的章节不能静默偏离；实现与文档冲突时，先按 [AGENTS.md](../../AGENTS.md) §3 处理冲突，再决定修代码或更新契约。使用者视角的 schema 参考与 jq 示例见[JSON 输出参考](../reference/json-output.md)。

## 1. 定位与边界

机器契约回答一个问题：**任何程序（CI、IDE、外部宿主、Agent）如何稳定消费 Flydb CLI 的结果**。它是路线图阶段二的核心交付，也是阶段三 MCP 适配与阶段五 Plan→Validate→Approval→Apply 协议的直接前置——MCP 适配层预期只做协议翻译，不重写领域语义。

契约覆盖五个面：

| 面 | 事实来源 |
|---|---|
| 命令集合与语义 | [05 命令语义](05-commands.md)、[命令参考](../reference/commands.md) |
| 配置键 | [06 §2](06-config-cli.md)、[配置项参考](../reference/configuration.md) |
| 错误码 | [06 §5](06-config-cli.md)、[错误码参考](../reference/errors.md) |
| 退出码 | [06 §5](06-config-cli.md) |
| JSON 输出 schema | 本文档与 [JSON 输出参考](../reference/json-output.md) |

实现收敛在 `flydb-cli`（`output/json/JsonWriter` 与 `output/json/JsonRenderers`），不触碰 `flydb-core`：所有命令结果领域对象在渲染前已存在，JSON 是纯渲染层。`flydb-cli` 不因此引入任何第三方依赖。

## 2. 输出通道分离（契约）

- **stdout**：`--json` 下恰好一行紧凑 JSON 文档（信封），以 `\n` 结尾。不输出任何其他内容。
- **stderr**：人类诊断。core 进度日志（`LogFactory` 默认 `SystemErrLog`）、错误原文、`-X` 异常栈都在 stderr。
- **编码**：JSON 内容为 UTF-8。CLI 默认以 UTF-8 包装 `System.out`/`System.err`（JDK 18+ 由 JEP 400 本就如此；旧 JDK 的 GBK 控制台上是行为变化，见 CHANGELOG）。
- **`--help` 与用法文本永远是文本**，不受 `--json` 影响；机器不应解析帮助输出。
- **SIGINT 例外**：Ctrl+C 走 `InterruptCoordinator` 直接 `System.exit(5)`，不保证输出信封。消费者必须把"没有信封 + 退出码 5"当作正常的中断信号。

## 3. 信封 schema（契约）

成功信封（所有命令共用前四个字段，顺序固定）：

```json
{"protocolVersion":1,"command":"migrate","status":"success","exitCode":0, ...载荷字段}
```

失败信封：

```json
{"protocolVersion":1,"command":"migrate","status":"error","exitCode":4,
 "error":{"code":"FLYDB-4002","detail":"必须提供 flydb.url","problems":[]}}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `protocolVersion` | 整数 | 契约版本，当前为 `1` |
| `command` | 字符串或 null | 叶子命令名（`migrate`）；解析失败无法定位命令时为 null |
| `status` | 字符串 | `success` 或 `error` |
| `exitCode` | 整数 | 与进程退出码一致（06 §5 的 0–5） |
| `error.code` | 字符串或 null | Flydb 错误码（`FLYDB-xxxx`）；参数用法错误与非 Flydb 异常为 null，只能凭 `exitCode` 分类 |
| `error.detail` | 字符串或 null | 动态详情，与文本模式同样过统一脱敏（密码与 URL 内嵌凭据替换为 `****`） |
| `error.problems` | 数组 | 校验类失败（`FlydbValidationException`）逐条 `{code, detail}`；其余为空数组 |

### 3.1 各命令载荷（契约）

| 命令 | 载荷字段 |
|---|---|
| `version` | `version`（产品版本字符串） |
| `migrate` | `executed`（脚本名数组）、`targetVersionReached`、`totalExecutionTimeMillis`、`warnings` |
| `migrate`/`undo` + `--dry-run` | `dryRun:true`、`plan` 摘要对象（Plan Artifact v1，见[11](11-plan-artifact.md)）、`migrations:[{script,type,version,description,checksum,statementCount,statements:[{lineNumber,sql}]}]`；`sql` 与文本 dry-run 同样脱敏 |
| `info` | `databaseName`、`url`（脱敏）、`historyTable`、`current`（已应用最高版本，可 null）、`migrations` 数组 |
| `info` 单条迁移 | `version`（可重复迁移为 null）、`description`、`type`、`script`、`checksum`、`installedOn`、`executionTimeMillis`、`state` |
| `validate` | 无载荷字段 |
| `baseline` | `baselineVersion` |
| `repair` | `removedFailedRecords`、`alignedChecksums`（数组） |
| `undo` | `undoneVersion`、`executionTimeMillis` |
| `init` | `createdFiles`（相对工作目录的路径数组） |
| `clean` | 无载荷字段 |

类型与取值规则：

- **状态 token 用 `MigrationState` 枚举名**：`PENDING`、`OUT_OF_ORDER`、`SUCCESS`、`FAILED`、`MISSING`、`OUTDATED`、`FUTURE`、`BASELINE`、`UNDONE`。中文状态名是文本表格的展示层，不进 JSON。
- **类型 token 用 `MigrationType` 枚举名**：`SQL`、`JDBC`、`BASELINE`、`UNDO_SQL`。
- `installedOn` 为 ISO-8601 本地时间（`yyyy-MM-dd'T'HH:mm:ss`），未应用为 null。
- 数值字段为整型；未知/不适用为 JSON null，不用哨兵字符串。

## 4. protocolVersion 语义（契约）

- **同一版本内向后兼容**：允许新增字段；消费者**必须忽略未知字段**。禁止改名、删除字段、改变类型或语义。
- **破坏性变更必须递增 `protocolVersion`**，并在 [JSON 输出参考](../reference/json-output.md) 与 CHANGELOG 中说明迁移方式。
- 字段顺序固定（便于人工比对与精确字符串测试），但**消费者不得依赖字段顺序**。
- 产品版本与契约版本独立演进：`version` 信封同时携带两者，消费者应各自判断。

## 5. 交互与输出模式（契约）

- **`--json` 模式零交互**：密码、`clean` 目标库名确认、`init` 提问一律不发起，等同非交互终端走既有报错分支（`FLYDB-4002`/`FLYDB-4003`）。不为此新增错误码。
- `--json` 不改变命令语义、锁行为与退出码；与 `-q` 正交（信封始终输出到 stdout）。
- `--json` 与 `--dry-run`、版本选择、路径过滤等可组合。

## 6. 回归策略

机器契约的测试形态是**精确字符串断言**：`JsonRenderersTest` 对每个信封断言完整 JSON 字符串，任何字段改名、删除或顺序调整都会失败——这类变更必须先递增 `protocolVersion` 并更新本契约。`FlydbCliTest` 端到端覆盖 stdout 单行性、stderr 分离与错误信封映射。渲染器是纯函数（入参为领域结果对象），不需要数据库即可测试。
