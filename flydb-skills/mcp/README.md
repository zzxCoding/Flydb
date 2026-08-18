# flydb-mcp

Flydb 的 MCP（Model Context Protocol）Adapter。以受控子进程调用 `flydb --json`，
向 MCP 宿主（Claude Code、Cursor、Codex 等）提供安全的数据库迁移工具。

- **协议 seam，不是第二套迁移实现**：领域真相由 Java Core/CLI 产生，Adapter 只翻译协议；
- **只映射 Flydb 领域命令**：不提供 `execute_sql`、任意命令名或 `rawArgs`，永不暴露 `clean`；
- **写入默认不注册**：`migrate`/`baseline`/`repair`/`undo` 只有操作者设置
  `FLYDB_MCP_ENABLE_WRITES=true` 才会出现在 `tools/list`（fail closed）；
- **独立 SemVer**：以 `package.json` 的 `flydb` 字段声明支持的 CLI 版本范围与 protocolVersion。

安装与配置见仓库文档：[MCP 工具参考](../../../docs/reference/mcp-tools.md)、
[MCP 接入指南](../../../docs/getting-started/mcp-adapter.md)。

## 开发

```bash
npm install
npm run build        # 产出 dist/server.mjs（单文件 bundle，无运行时依赖）
npm test             # 单元测试（内存 fake CliRunner + 真实子进程用例）
npm run test:e2e     # 跨运行时端到端（需 FLYDB_E2E_CLI 指向真实 bin/flydb）
```
