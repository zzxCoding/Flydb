# MCP 接入指南

把 Flydb 挂进 Claude Code、Cursor、Codex 等支持 [MCP](https://modelcontextprotocol.io/) 的 Agent 宿主。工具清单、输入输出与安全模型以[MCP 工具参考](../reference/mcp-tools.md)为准。

## 前置条件

1. Node.js ≥ 20（仅 MCP 宿主侧需要；普通 CLI/starter 用户不需要）；
2. Flydb CLI ≥ 0.3.0 发行包（含 Java 8+ 运行时）；
3. 目标数据库的 JDBC 驱动已放入发行包 `drivers/`（MCP 调用期间不联网下载驱动）。

## 安装方式

### 方式一：Agent Plugin（`flydb-skills` 插件包）

`flydb-skills` 是合法的 [Agent Plugins 1.0.0](https://agent-plugins.org/specification) 插件包，根目录 `mcp.json` 已声明 stdio server：

```json
{
  "$schema": "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
  "mcpServers": {
    "flydb": {
      "type": "stdio",
      "command": "node",
      "args": ["${PLUGIN_ROOT}/mcp/dist/server.mjs"],
      "cwd": "${PLUGIN_ROOT}"
    }
  }
}
```

插件宿主直接装载 `flydb-skills/` 目录即可（`mcp/dist/server.mjs` 为已构建产物，无需 `npm install`）。从源码仓库使用时需先构建：

```bash
cd flydb-skills/mcp && npm install && npm run build
```

### 方式二：npm / npx

```bash
npx flydb-mcp        # 一次性启动（发布后可用；registry 名称以发布说明为准）
```

### 方式三：宿主手动配置

以 Claude Code / 通用 mcpServers JSON 为例：

```json
{
  "mcpServers": {
    "flydb": {
      "type": "stdio",
      "command": "npx",
      "args": ["flydb-mcp"],
      "env": {
        "FLYDB_CLI": "/opt/flydb-cli-0.3.0/bin/flydb",
        "FLYDB_PASSWORD": "由宿主 secret 注入"
      }
    }
  }
}
```

也可用 `FLYDB_HOME` 指向发行包根目录，或把发行包 `bin/` 加入 `PATH`；密码优先用 `FLYDB_PASSWORD`、`${env:VAR}` 或 `flydb.password.file` 注入，不进入 tool 输入。

## 验证安装

1. 宿主连接后执行 `flydb_version`：应返回 `structuredContent.version ≥ 0.3.0`；
2. 执行 `flydb_info` / `flydb_validate`（传入项目绝对 `workingDirectory` 与 `configPath`）确认只读链路；
3. `tools/list` 默认只有 5 个只读工具；看不到 `flydb_migrate` 等写入工具是**预期行为**。

## 开启写入工具（安装时知情决策）

默认不注册 `migrate`/`baseline`/`repair`/`undo`。操作者确认以下事项后，在 server 环境变量中一次性设置：

```json
"env": {"FLYDB_MCP_ENABLE_WRITES": "true"}
```

- 仅接受字面值 `true`（不区分大小写）；缺失或任何其他值一律视为关闭（fail closed）；
- 开启后即可通过 MCP 完成迁移闭环（查状态 → 预演 → 执行 → 复核）；server 不提供逐次授权，宿主应保持逐次确认、不建议 allowlist；
- 执行规范：先 `flydb_plan_migrate` 核对计划（记录返回的 `plan.id`），获得用户明确授权后再调用 `flydb_migrate`。

## 故障排查

| 现象 | 处理 |
|---|---|
| 启动即退出，提示“未找到 flydb CLI” | 设置 `FLYDB_CLI`（绝对路径）或 `FLYDB_HOME`，或将 `bin/` 加入 PATH |
| 启动即退出，提示“版本过低” | 升级 CLI 到 ≥ 0.3.0 |
| `FLYDB_MCP-0001` | CLI 路径存在但无法执行（权限/架构） |
| `FLYDB_MCP-0002` | 调用超时；用 `FLYDB_MCP_TIMEOUT_MS` 调整（长迁移建议调大） |
| `FLYDB_MCP-0004/0005/0006` | CLI 输出违反机器契约，按[JSON 输出参考](../reference/json-output.md)排查，勿让包装脚本污染 stdout |
| 驱动缺失 | 把目标库 JDBC 驱动放入发行包 `drivers/`（MCP 固定 `--driver-download never`） |

完整 Adapter 诊断码表见[MCP 工具参考](../reference/mcp-tools.md)。
