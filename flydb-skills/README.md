# flydb-skills

Flydb 的 Agent Skills 集合。Skill 负责把重复的操作流程交给 Agent，项目文档仍然是命令、配置和数据库接入行为的唯一事实来源，避免 Skill 内复制一份会过期的 CLI 手册。

本目录同时是一个合法的 [Agent Plugins 1.0.0](https://agent-plugins.org/specification) 插件包：根目录的 [`plugin.json`](plugin.json) 是插件清单，`skills/` 下是组件技能，[`mcp.json`](mcp.json) 声明 stdio MCP server（入口 [`mcp/dist/server.mjs`](mcp/dist/server.mjs)，需先在 `mcp/` 内构建）。支持该规范的宿主（ChatGPT、Codex、Cursor、Copilot、VS Code 等）可以直接以插件形式装载本目录。`plugin.json` 的 `version` 随 Flydb 发布版本同步维护，`name: flydb` 是规范层面永不重分配的包标识。

MCP server 把 Flydb 暴露为 9 个领域工具（写入工具默认不注册，`FLYDB_MCP_ENABLE_WRITES=true` 显式开启，永不暴露 `clean`）。工具清单、安全模型与宿主配置见 [MCP 工具参考](../docs/reference/mcp-tools.md)与[MCP 接入指南](../docs/getting-started/mcp-adapter.md)；npm 包 `flydb-mcp` 是独立分发入口，与插件内置运行时复用同一构建产物。

## Agent 首次接入

请先阅读仓库根目录的 [`AGENTS.md`](../AGENTS.md)。它会引导你安装或启用 `flydb-cli`、读取 CLI/JDBC 文档，并从 `version`、`validate`、`--dry-run migrate` 开始；如果当前环境不能自动发现 Skill，直接读取下面的 `SKILL.md` 即可。

如果你是人类用户，也可以把下面这段话复制给 Agent：

> 我正在使用 Flydb。请先阅读并遵循 [AGENTS.md](https://github.com/zzxCoding/Flydb/blob/main/AGENTS.md)，然后安装或启用 `flydb-cli` Skill。安装完成后先确认 `bin/flydb version`；涉及迁移时先执行 `validate` 和 `--dry-run migrate`。不要把密码写入命令、日志或 SQL；未经我明确授权，不要执行会修改数据库的命令。完成后告诉我 Skill 的安装位置和下一步。

## 当前技能

| Skill | 用途 | 入口 |
|---|---|---|
| `flydb-cli` | 使用和排查 Flydb CLI，覆盖初始化、驱动接入、迁移、校验、状态、修复和撤销 | [`skills/flydb-cli/SKILL.md`](skills/flydb-cli/SKILL.md) |

## 多 Agent 兼容

`flydb-cli` 使用开放的 Agent Skills 目录格式：一个技能目录包含 `SKILL.md`，可选 `references/`、`scripts/` 和 `assets/`。因此同一份 Skill 面向以下主流 Agent 提供跨工具复用：

| Agent | 兼容方式 | 常见发现/安装位置 |
|---|---|---|
| Claude Code | 读取 `SKILL.md` | `.claude/skills/` 或用户级 skills 目录 |
| OpenAI Codex | 读取 `SKILL.md`，支持显式 `$skill-name` 或自动匹配 | `.agents/skills/` 或 Codex 用户 skills 目录 |
| Gemini CLI | Agent Skills / `SKILL.md` | `.agents/skills/`、`.gemini/skills/` 或 `gemini skills install` |
| Kimi Code | Agent Skills / `SKILL.md` | `~/.config/agents/skills/`、`--skills-dir` 或项目 skills 目录 |
| ZCode | Skill / `SKILL.md` | `~/.zcode/skills/`，也支持从其他 Agent 导入 |
| Hermes Agent | Skills / `SKILL.md` | `~/.hermes/skills/` 或 Hermes Skills Hub |
| Pi | Agent Skills standard / `SKILL.md` | `.pi/skills/`、`.agents/skills/` 或 `~/.pi/agent/skills/` |

这里的“兼容”指 Skill 文件格式和渐进式加载模型兼容；各 Agent 的安装命令、权限确认、刷新方式和版本能力仍以其官方文档为准。Flydb 不声称替各 Agent 提供运行时、模型或插件认证。

官方能力说明：[Claude Code](https://code.claude.com/docs/en/agent-sdk/skills)、[Codex](https://developers.openai.com/codex/skills)、[Gemini CLI](https://geminicli.com/docs/cli/using-agent-skills/)、[Kimi Code](https://moonshotai.github.io/kimi-cli/en/customization/skills.html)、[ZCode](https://zcode.z.ai/en/docs/skill)、[Hermes Agent](https://hermes-agent.noasresearch.com/docs/getting-started/quickstart)、[Pi](https://pi.dev/docs/latest/skills)。

## 使用方式

将 [`skills/flydb-cli`](skills/flydb-cli) 复制到支持 Agent Skills 的技能目录，或在 Flydb 源码仓库/CLI 发行包中直接使用。发行包同时附带版本匹配的 `docs/`；复制 Skill 后应保留发行包路径，Agent 会优先从目标发行包读取文档，再决定 CLI 命令。它不会把密码写进命令行、日志或迁移脚本。

使用本 Skill 处理信创或新型 JDBC 数据库时，先阅读[JDBC 数据库快速接入](../docs/getting-started/jdbc-integration.md)，确认驱动、方言和迁移语义，再执行 CLI 操作。

## 文档来源

`flydb-cli` 是一个薄的操作编排 Skill，不复制命令表。它引用以下仓库文档：

- [CLI 命令参考](../docs/reference/commands.md)
- [配置项参考](../docs/reference/configuration.md)
- [错误码参考](../docs/reference/errors.md)
- [JSON 输出参考](../docs/reference/json-output.md)
- [JDBC 数据库快速接入](../docs/getting-started/jdbc-integration.md)
- [数据库上手指南索引](../docs/getting-started/README.md)
- [配置体系与 CLI 设计](../docs/design/06-config-cli.md)

修改 CLI 选项、错误码、驱动目录或数据库支持边界时，应先更新上述项目文档，再检查 Skill 是否仍然只引用、不重复描述。

## 开发与校验

在仓库根目录执行：

```bash
python3 "${HOME}/.agents/skills/skill-creator/scripts/quick_validate.py" \
  flydb-skills/skills/flydb-cli
```

评测提示词与可验证预期位于 [`skills/flydb-cli/evals/evals.json`](skills/flydb-cli/evals/evals.json)，覆盖基本迁移、外部 locations、范围/版本族选择、发现完整性、业务模板占位符、MISSING、驱动诊断和 clean 安全边界。

项目复用仓库根目录的 [Apache-2.0 许可证](../LICENSE)。
