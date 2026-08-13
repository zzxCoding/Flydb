# flydb-skills

Flydb 的 Agent Skills 集合。Skill 负责把重复的操作流程交给 Agent，项目文档仍然是命令、配置和数据库接入行为的唯一事实来源，避免 Skill 内复制一份会过期的 CLI 手册。

## 当前技能

| Skill | 用途 | 入口 |
|---|---|---|
| `flydb-cli` | 使用和排查 Flydb CLI，覆盖初始化、驱动接入、迁移、校验、状态、修复和撤销 | [`skills/flydb-cli/SKILL.md`](skills/flydb-cli/SKILL.md) |

## 使用方式

将 [`skills/flydb-cli`](skills/flydb-cli) 复制到支持 Agent Skills 的技能目录，或在当前 Flydb 工作区直接使用。触发该 Skill 后，Agent 会先定位 Flydb 仓库并读取对应文档，再决定 CLI 命令；不会把密码写进命令行、日志或迁移脚本。

使用本 Skill 处理信创或新型 JDBC 数据库时，先阅读[JDBC 数据库快速接入](../docs/getting-started/jdbc-integration.md)，确认驱动、方言和迁移语义，再执行 CLI 操作。

## 文档来源

`flydb-cli` 是一个薄的操作编排 Skill，不复制命令表。它引用以下仓库文档：

- [CLI 命令参考](../docs/reference/commands.md)
- [配置项参考](../docs/reference/configuration.md)
- [错误码参考](../docs/reference/errors.md)
- [JDBC 数据库快速接入](../docs/getting-started/jdbc-integration.md)
- [数据库上手指南索引](../docs/getting-started/README.md)
- [配置体系与 CLI 设计](../docs/design/06-config-cli.md)

修改 CLI 选项、错误码、驱动目录或数据库支持边界时，应先更新上述项目文档，再检查 Skill 是否仍然只引用、不重复描述。

## 开发与校验

在仓库根目录执行：

```bash
python3 /Users/xuan/.agents/skills/skill-creator/scripts/quick_validate.py \
  flydb-skills/skills/flydb-cli
```

测试提示词草案位于 [`skills/flydb-cli/evals/evals.json`](skills/flydb-cli/evals/evals.json)。它们用于后续评估 Skill 是否正确触发和遵守 CLI 安全边界。

项目复用仓库根目录的 [MIT 许可证](../LICENSE)。
