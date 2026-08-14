# Flydb Agent Onboarding

This file is the first-read onboarding contract for AI coding agents working in
the Flydb repository. If you are Claude Code, OpenAI Codex, Gemini CLI, Kimi
Code, ZCode, Hermes Agent, Pi, or another Agent Skills-compatible agent, read
this file before choosing a command or changing a migration.

If the task mentions Flydb CLI, `flydb.conf`, `drivers/`, JDBC migrations, or
database schema changes, enable `flydb-cli` before selecting or executing a
command. For a documentation-only task, reading this file and the relevant
source docs is sufficient.

## 1. Read the skill and the source documentation

Start from the repository root and read these files in order:

1. [`flydb-skills/skills/flydb-cli/SKILL.md`](flydb-skills/skills/flydb-cli/SKILL.md)
2. [`docs/reference/commands.md`](docs/reference/commands.md)
3. [`docs/reference/configuration.md`](docs/reference/configuration.md)
4. [`docs/getting-started/jdbc-integration.md`](docs/getting-started/jdbc-integration.md) when a vendor, Xinchuang, or new JDBC database is involved
5. The relevant database guide under [`docs/getting-started/`](docs/getting-started/)

`SKILL.md` defines the operating workflow and safety boundary. The `docs/`
files are the source of truth for command options, configuration, errors,
drivers, dialects, and database-specific semantics. Do not reconstruct CLI
options from memory and do not copy the CLI manual into a new Skill document.

## 2. Install or enable `flydb-cli`

The Skill source is:

```text
flydb-skills/skills/flydb-cli
```

When the current Agent supports project-local skills, the reproducible default
is `.agents/skills/flydb-cli`:

```bash
skill_source="$PWD/flydb-skills/skills/flydb-cli"
skill_target="$PWD/.agents/skills/flydb-cli"

if [ -e "$skill_target" ]; then
  echo "Skill already exists; inspect it before replacing: $skill_target"
else
  mkdir -p "$PWD/.agents/skills"
  cp -R "$skill_source" "$skill_target"
fi
```

Use the Agent's official project-level directory or install/import command when
it has one. The usual locations for this Skill are:

| Agent | Project-level location or option |
|---|---|
| Claude Code | `.claude/skills/flydb-cli` |
| OpenAI Codex | `.agents/skills/flydb-cli` |
| Gemini CLI | `.agents/skills/flydb-cli` or `.gemini/skills/flydb-cli` |
| Kimi Code | `--skills-dir` pointing at `.agents/skills` |
| ZCode | use its Skill import flow or `~/.zcode/skills/flydb-cli` |
| Hermes Agent | `~/.hermes/skills/flydb-cli` or its Skills Hub |
| Pi | `.pi/skills/flydb-cli` or `.agents/skills/flydb-cli` |

Prefer a project-local installation so the Skill version is reviewable with
the checkout. Only write to a user-level directory when the user has asked for
that installation. If Skill discovery is unavailable, read the source
`SKILL.md` directly and follow it; do not download an unreviewed replacement.

After installation, validate the Skill structure when the validator is
available:

```bash
python3 /Users/xuan/.agents/skills/skill-creator/scripts/quick_validate.py \
  flydb-skills/skills/flydb-cli
```

## 3. First-use workflow

For a Flydb CLI task:

1. Confirm the repository or distribution path. A source checkout is not an
   installed CLI; use `bin/flydb` from a built distribution when available.
2. Read the command and configuration references required by the task. The CLI resolves drivers from `drivers/`, the runtime classpath, Maven local repository, Flydb cache, then Maven effective mirrors/private repositories; use `flydb.offline=true` when network access is forbidden.
3. Run `bin/flydb version` before database work.
4. For a migration, run `validate` and then `--dry-run migrate` first.
5. Ask for explicit authorization before `migrate`, `baseline`, `repair`,
   `undo`, or `clean` changes a database. Keep `clean` disabled unless the
   user explicitly confirms the destructive operation.
6. Report the CLI path, redacted database target, command, dry-run/write
   status, exit code, and verification result.

`flydb.password` 支持直接配置明文值（仅建议本地临时测试）；生产和共享环境使用
`FLYDB_PASSWORD`、`${env:VAR}` 或 `flydb.password.file`。
Never put passwords in command arguments, logs, Skill output, or SQL files.

## 4. New JDBC and Xinchuang databases

Read [`docs/getting-started/jdbc-integration.md`](docs/getting-started/jdbc-integration.md)
before selecting a dialect. A JDBC driver and a Flydb dialect are separate
concerns. MySQL or Oracle syntax compatibility alone is not enough to reuse a
family: check history-table DDL, identifier rules, DDL transaction behavior,
locking, and script splitting. If the semantics differ, use a distinct
`DatabaseType` SPI implementation instead of forcing a detection result.

Do not download, commit, or redistribute vendor JDBC drivers. Test a new
database on an authorized instance with `validate`, `--dry-run migrate`, and a
harmless migration before describing it as supported. Flydb's compatibility
statement means reusable Skill format and documented integration paths; it is
not runtime, model, plugin, or vendor certification.

## 5. Keep the onboarding contract current

When CLI behavior, configuration keys, error codes, driver loading, or database
support changes, update the relevant `docs/` page first. Then check that
`flydb-skills/skills/flydb-cli/SKILL.md` still links to the source documentation
without duplicating stale command details. Keep this file focused on Agent
discovery, installation, and safe first use.
