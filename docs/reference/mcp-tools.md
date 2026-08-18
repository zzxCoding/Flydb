# MCP 工具参考

Flydb MCP Adapter（`flydb-skills/mcp/`，npm 包 `flydb-mcp`）把 Flydb CLI 暴露为 [MCP](https://modelcontextprotocol.io/) tools。本文是工具清单、输入输出约定与安全模型的事实来源；CLI 信封 schema 以[JSON 输出参考](json-output.md)为准，命令语义以[命令参考](commands.md)为准，错误码以[错误码参考](errors.md)为准，计划摘要以[Plan Artifact 设计](../design/11-plan-artifact.md)为准。

## 定位

Adapter 只是协议 seam：每次 tool 调用以受控子进程执行 `flydb --json <命令>`（参数数组、不经过 shell），解析并校验 CLI 信封后映射为 MCP tool result。TypeScript 内不实现迁移发现、版本选择、校验或任何数据库写入逻辑；领域真相由 Java Core/CLI 产生。

## 运行要求与定位

- MCP 宿主侧需要 Node.js ≥ 20；CLI 侧需要 Java 8+ 与 Flydb CLI ≥ 0.3.0。普通 CLI/starter 用户不需要 Node。
- CLI 定位顺序固定：`FLYDB_CLI`（绝对路径）→ `FLYDB_HOME/bin/flydb`（Windows 为 `flydb.bat`）→ `PATH` 中的 `flydb`。
- 启动时执行 `--json version` 握手：产品版本 ≥ 0.3.0 且 `protocolVersion=1` 才就绪；否则拒绝启动并在 stderr 输出脱敏诊断。
- Adapter 使用独立 SemVer；`package.json` 的 `flydb` 字段声明支持的 CLI 范围（`>=0.3.0 <0.4.0`）与 `protocolVersion`。
- Adapter 自身日志只写 stderr，MCP stdout 通道只承载协议消息。

环境变量：

| 变量 | 作用 | 默认 |
|---|---|---|
| `FLYDB_CLI` | CLI 可执行文件绝对路径 | 见定位顺序 |
| `FLYDB_HOME` | CLI 发行包根目录 | — |
| `FLYDB_MCP_ENABLE_WRITES` | 显式开启写入工具（仅字面值 `true`，不区分大小写；缺失或非法一律关闭，fail closed） | 未设置 |
| `FLYDB_MCP_TIMEOUT_MS` | 单次 tool 调用超时毫秒数（正整数；非法值回退默认并告警） | `600000` |

## 工具清单

| 工具 | CLI 动作 | 写入状态 | 注册 |
|---|---|---|---|
| `flydb_version` | `--json version` | 不连接数据库 | 始终 |
| `flydb_info` | `--json info` | 只读 | 始终 |
| `flydb_validate` | `--json validate` | 只读 | 始终 |
| `flydb_plan_migrate` | `--json --dry-run migrate` | 只读预演（Plan Artifact） | 始终 |
| `flydb_plan_undo` | `--json --dry-run undo` | 只读预演（Plan Artifact） | 始终 |
| `flydb_migrate` | `--json migrate` | 持迁移锁写库 | 默认不注册 |
| `flydb_baseline` | `--json baseline` | 写历史表（仅追加） | 默认不注册 |
| `flydb_repair` | `--json repair` | 修改历史表 | 默认不注册 |
| `flydb_undo` | `--json undo` | 持锁执行回退 SQL | 默认不注册 |

写入工具未开启时 `tools/list` **不包含**它们（而不是注册后调用报错），默认安装的暴露面与纯只读完全一致。annotations：读取/预演工具 `readOnlyHint: true`；`flydb_migrate`、`flydb_repair`、`flydb_undo` 标注 `destructiveHint: true`（迁移 SQL 可含破坏性语句），`flydb_baseline` 标注 `destructiveHint: false`。

### 输入约定

- `flydb_version` 无输入。
- 其余工具只接收两个白名单字段：`workingDirectory`（绝对路径，子进程 cwd）与 `configPath`（绝对路径，Adapter 以 `-c` 显式传入，不做隐式配置搜索）；`additionalProperties: false`，其余任何字段（密码、JDBC URL、环境变量、`rawArgs`）一律拒绝。
- 数据库工具固定追加 `--driver-download never`：调用期间不联网下载驱动，所需驱动必须预先放入发行包 `drivers/`、classpath 或本地制品库。
- 密码由 MCP 宿主环境、`${env:VAR}` 或 `flydb.password.file` 注入，不进入 tool 输入与参数。

### 输出与错误映射

每个工具声明与 CLI 信封一致的开放 `outputSchema`。结果映射固定：

| CLI 结果 | MCP tool result |
|---|---|
| 合法成功信封 | 信封对象进 `structuredContent`，同一 JSON 文本进 `content`，`isError` 省略 |
| 合法错误信封（非零退出码） | 同上，`isError: true`，保留 `FLYDB-xxxx` 领域错误码 |
| CLI 缺失、无法启动、超时、取消、stdout 非法、协议版本不兼容、退出码不一致 | `isError: true` 的 Adapter 诊断（见下表），不伪造 `FLYDB-xxxx` |
| 未知 tool（含未开启的写入工具） | MCP/JSON-RPC 协议错误（`-32602`） |

Adapter 诊断码（`adapterError.code`，`FLYDB_MCP-xxxx` 命名空间）：

| 码 | 含义 |
|---|---|
| `FLYDB_MCP-0001` | 找不到或无法启动 CLI |
| `FLYDB_MCP-0002` | 执行超时，子进程已终止 |
| `FLYDB_MCP-0003` | 请求取消，子进程已终止 |
| `FLYDB_MCP-0004` | stdout 非法（空、非 JSON、多文档、非对象） |
| `FLYDB_MCP-0005` | `protocolVersion` 不兼容 |
| `FLYDB_MCP-0006` | 进程退出码与信封 `exitCode`/`status` 不一致 |
| `FLYDB_MCP-0007` | 信封核心字段校验失败 |
| `FLYDB_MCP-0008` | tool 输入未通过白名单校验 |
| `FLYDB_MCP-0009` | CLI 产品版本低于支持下限 |
| `FLYDB_MCP-0099` | Adapter 内部错误 |

诊断文本与 stderr 摘录沿用与 CLI 一致的脱敏规则（`password=…`、URL 内嵌凭据替换为 `****`）。

## 安全模型（如实分层）

- **server 结构性强制**：永不注册 `clean`；`init` 不进 MCP；无 `execute_sql`/任意命令名/`rawArgs`；参数白名单；固定 `--driver-download never`；写入工具未开启时不存在。
- **server 不承诺的部分**：写入工具的逐次授权。开启 `FLYDB_MCP_ENABLE_WRITES=true` 后，调用方可以直接执行写工具；server 不强制“先 plan 后写”，也不实现审批流。
- **纵深防护（非协议保证）**：宿主对写入工具逐次确认、不建议 allowlist；Skill 执行规范约束“先 `flydb_plan_migrate` 核对计划（记录 `plan.id`）并获得用户明确授权，再调用 `flydb_migrate`”。阶段五的 Plan → Validate → Risk → Approval → Apply 协议是对写入路径的升级，不是前提。

## 开发与验证

```bash
cd flydb-skills/mcp
npm install && npm run build     # 产出 dist/server.mjs（单文件，无运行时依赖）
npm test                         # 单元测试（fake CliRunner + 真实子进程）
FLYDB_E2E_CLI=<bin/flydb> npm run test:e2e   # 真实 CLI + 官方 MCP client 跨运行时
FLYDB_E2E_WRITE=1 FLYDB_E2E_CLI=<bin/flydb> npm run test:e2e  # 追加写入闭环（Docker 测试库）
```

接入配置与宿主样例见[MCP 接入指南](../getting-started/mcp-adapter.md)。
