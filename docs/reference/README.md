# Flydb 参考文档

这里记录可直接查阅的稳定接口与运行约定：

- [配置项参考](configuration.md)：CLI、环境变量、`flydb.conf` 与 Spring Boot `flydb.*` 配置。
- [错误码参考](errors.md)：稳定错误码、常见原因、修复动作和 CLI 退出码。
- [CLI 命令参考](commands.md)：全局选项、命令、锁范围和常用流程。
- [命令与 CLI 设计](../design/06-config-cli.md)：命令语义、驱动加载和发行包布局。

接入新数据库的操作流程见[JDBC 数据库快速接入](../getting-started/jdbc-integration.md)；方言扩展接口见[数据库方言层设计](../design/03-dialects.md)。

Agent 自动化入口见仓库根目录的 [`AGENTS.md`](../../AGENTS.md) 和 [`flydb-skills`](../../flydb-skills/README.md)。其中的 [`flydb-cli`](../../flydb-skills/skills/flydb-cli/SKILL.md) 按开放 `SKILL.md` 格式面向 Claude Code、OpenAI Codex、Gemini CLI、Kimi Code、ZCode、Hermes Agent、Pi 等主流 Agent 复用；它只引用本目录和数据库上手文档，不另行维护命令参数。

配置优先级统一为：

```text
CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值
```
