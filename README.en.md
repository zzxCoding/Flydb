English | [中文](./README.md)

<p align="center">
  <img src="./docs/assets/flydb-mascot-banner.png" alt="Flydb Data Courier mascot" width="100%">
</p>

# Flydb

[![CI](https://github.com/zzxCoding/Flydb/actions/workflows/ci.yml/badge.svg)](https://github.com/zzxCoding/Flydb/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/zzxCoding/Flydb)](https://github.com/zzxCoding/Flydb/releases/latest)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](./LICENSE)
![Java 8+](https://img.shields.io/badge/Java-8%2B-blue)
[![LINUX DO](https://img.shields.io/badge/LINUX-DO-FFB003.svg?logo=data:image/svg%2bxml;base64,DQo8c3ZnIHhtbG5zPSJodHRwOi8vd3d3LnczLm9yZy8yMDAwL3N2ZyIgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiPjxwYXRoIGQ9Ik00Ni44Mi0uMDU1aDYuMjVxMjMuOTY5IDIuMDYyIDM4IDIxLjQyNmM1LjI1OCA3LjY3NiA4LjIxNSAxNi4xNTYgOC44NzUgMjUuNDV2Ni4yNXEtMi4wNjQgMjMuOTY4LTIxLjQzIDM4LTExLjUxMiA3Ljg4NS0yNS40NDUgOC44NzRoLTYuMjVxLTIzLjk3LTIuMDY0LTM4LjAwNC0yMS40M1EuOTcxIDY3LjA1Ni0uMDU0IDUzLjE4di02LjQ3M0MxLjM2MiAzMC43ODEgOC41MDMgMTguMTQ4IDIxLjM3IDguODE3IDI5LjA0NyAzLjU2MiAzNy41MjcuNjA0IDQ2LjgyMS0uMDU2IiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZWNlY2VjO2ZpbGwtb3BhY2l0eToxIi8+PHBhdGggZD0iTTQ3LjI2NiAyLjk1N3EyMi41My0uNjUgMzcuNzc3IDE1LjczOWE0OS43IDQ5LjcgMCAwIDEgNi44NjcgMTAuMTU3cS00MS45NjQuMjIyLTgzLjkzIDAgOS43NS0xOC42MTYgMzAuMDI0LTI0LjM4N2E2MSA2MSAwIDAgMSA5LjI2Mi0xLjUwOCIgc3R5bGU9InN0cm9rZTpub25lO2ZpbGwtcnVsZTpldmVub2RkO2ZpbGw6IzE5MTkxOTtmaWxsLW9wYWNpdHk6MSIvPjxwYXRoIGQ9Ik03Ljk4IDcwLjkyNmMyNy45NzctLjAzNSA1NS45NTQgMCA4My45My4xMTNRODMuNDI2IDg3LjQ3MyA2Ni4xMyA5NC4wODZxLTE4LjgxIDYuNTQ0LTM2LjgzMi0xLjg5OC0xNC4yMDMtNy4wOS0yMS4zMTctMjEuMjYyIiBzdHlsZT0ic3Ryb2tlOm5vbmU7ZmlsbC1ydWxlOmV2ZW5vZGQ7ZmlsbDojZjlhZjAwO2ZpbGwtb3BhY2l0eToxIi8+PC9zdmc+)](https://linux.do)

Flydb is a versioned schema migration tool for databases with JDBC drivers: built-in dialects for mainstream databases, first-class support for Chinese Xinchuang databases, and extension to niche JDBC databases through the `DatabaseType` SPI.

**Today**, Flydb 0.2 is a reliable migration runtime: commands such as `migrate`, `info`, `validate`, `baseline`, `repair`, `undo`, and `clean`, backed by concurrency locks, transaction semantics, checksum validation, and failure blocking with recovery; eight built-in dialects; and Spring Boot 2/3 starters. **The long-term direction** is a database change capability shared safely by humans and AI agents: agents decide *what* changes; Flydb guarantees *how* it changes safely. See the [roadmap](./ROADMAP.md) (in Chinese) for stage goals and current progress.

> **Scope boundary:** Flydb manages migration versions, execution safety, and database dialect behavior. It does not translate arbitrary vendor SQL into every database syntax. Keep separate migration directories for database families when their dialects differ; see the [multi-environment guide](./docs/getting-started/multi-environment.md#4-脚本仓库按数据库家族分目录).

## Why Flydb

- **Xinchuang databases as first-class citizens**: DM8 (Dameng), KingbaseES, openGauss, OceanBase, and TiDB ship as built-in dialects alongside MySQL, PostgreSQL, and Oracle. The CLI never bundles vendor drivers; it resolves them from `drivers/`, the runtime classpath, or a Maven repository, which fits drivers that cannot be distributed publicly.
- **Zero-dependency Java 8 core**: `flydb-core` has no third-party runtime dependencies (enforced by Maven Enforcer) and drops into any legacy Java 8 system; Boot 3 / Java 17 environments use a separate starter.
- **Friendly to humans and agents alike**: stable exit and error codes, `--dry-run` previews, non-interactive operation; the distribution ships an Agent Skill and docs matched to the CLI version.
- **Safe defaults**: `clean` is disabled by default and needs a double opt-in; failed migrations block subsequent runs; passwords come from environment variables or password files, never commands, logs, or SQL.

## Quick start

Prerequisites: Java 8 or newer, an existing target database, and a Java 8-compatible JDBC driver.

```bash
curl -LO https://github.com/zzxCoding/Flydb/releases/download/v0.3.2/flydb-cli-0.3.2.zip
unzip flydb-cli-0.3.2.zip
cd flydb-cli-0.3.2

# Example: place mysql-connector-j.jar into drivers/
cp /path/to/mysql-connector-j.jar drivers/

bin/flydb init \
  --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user \
  --database-type mysql \
  --yes

export FLYDB_PASSWORD='replace-me'
bin/flydb --dry-run migrate
bin/flydb migrate
bin/flydb info
bin/flydb validate
```

`init` generates `flydb.conf`, `db/migration/V1__init.sql`, and `drivers/README.md`, and never overwrites existing files. Passwords can also be supplied through `flydb.password=${env:DB_PASSWORD}` or `flydb.password.file=/run/secrets/db_password`; a plaintext `flydb.password` is only recommended for local throwaway testing.

## Database support

| Database family | Built-in dialect | Current verification level |
|---|---:|---|
| MySQL  | Yes | Automated contract tests; CLI distribution end-to-end verification |
| PostgreSQL | Yes | Automated contract tests |
| Oracle | Yes | Automated contract tests; end-to-end validation (validate / clean / migrate) completed on a licensed real instance |
| DM8 (Dameng) | Yes | Dialect and driver-metadata contract tests; real-environment certification pending |
| KingbaseES | Yes | Dialect and driver-metadata contract tests; real-environment certification pending |
| openGauss | Yes | Dialect and driver-metadata contract tests; real-environment certification pending |
| OceanBase | Oracle/MySQL family reuse | Oracle tenant end-to-end validated on a licensed real instance; MySQL tenant under lightweight compatibility tests |
| TiDB | MySQL family reuse | Lightweight compatibility tests; real-environment coverage growing |
| Other JDBC databases | Extensible | Requires a JDBC driver and a `DatabaseType` SPI dialect |

See the [database getting-started guides](./docs/getting-started/README.md) for drivers, URLs, permissions, and known limitations per database. The status reflects current verification evidence, not vendor certification. The full matrix of modules, Java/Spring Boot versions, and database drivers is in the [compatibility matrix](./docs/reference/compatibility.md) (in Chinese). For vendor or Xinchuang JDBC databases, start with the [JDBC integration guide](./docs/getting-started/jdbc-integration.md).

## Roadmap

- [x] **Reliable migration runtime**: engine, 8 built-in dialects, CLI, Spring Boot starters, Agent Skill, the `v0.2.0` GitHub Release, and the `v0.2.1` Maven Central publishing
- [x] **DX and machine contract**: `--json` machine-readable output, protocolVersion contract versioning, CI integration docs, an Agent Plugins 1.0 package (`v0.3.0`; package managers and a Docker image on demand)
- [x] **Agent distribution**: an MCP adapter (TypeScript, nine domain tools, writes unregistered by default) plus the Plan Artifact v1 plan digest; the npm package ships with the next release
- [ ] **Brownfield change intelligence**: impact analysis, application reference scanning, coverage with explicit unknowns
- [ ] **Agent-safe change runtime**: a Plan → Validate → Risk → Approval → Apply → Verify protocol

The roadmap indicates direction, not delivery commitments; see [ROADMAP.md](./ROADMAP.md) (in Chinese) for details and product boundaries.

## Agent usage

If you are an agent, read the repository-root [`AGENTS.md`](./AGENTS.md) first, then install or enable the [`flydb-cli` Skill](./flydb-skills/skills/flydb-cli/SKILL.md) as it instructs before running commands; for migrations, run `validate` and `--dry-run migrate` first. The Skill is a thin orchestration layer that never duplicates the CLI manual; command, configuration, and error-code details are authoritative in [`docs/reference`](./docs/reference/README.md). The Skill follows the open `SKILL.md` format for reuse across Claude Code, Codex, Gemini CLI, ZCode, and other agents — see [`flydb-skills`](./flydb-skills/README.md). MCP-capable hosts can also call Flydb as MCP tools via [`mcp.json`](./flydb-skills/mcp.json) (write tools unregistered by default); see the [MCP tools reference](./docs/reference/mcp-tools.md) and the [setup guide](./docs/getting-started/mcp-adapter.md) (in Chinese).

The CLI distribution ZIP includes `AGENTS.md`, `docs/`, and `flydb-skills/`, so documentation and the Skill remain available with only the distribution and no source checkout. If you copy the Skill into an agent-specific directory, keep the distribution path so it can resolve those docs.

<details>
<summary>For human users: have your agent install and use the Flydb Skill</summary>

> I am using Flydb. Please read and follow [AGENTS.md](https://github.com/zzxCoding/Flydb/blob/main/AGENTS.md), then install or enable the `flydb-cli` Skill. After installation, confirm `bin/flydb version`; for migration tasks, run `validate` and `--dry-run migrate` first. Do not put passwords in commands, logs, or SQL, and do not run database-changing commands without my explicit authorization. Report the Skill installation path and the next step when done.

</details>

## Use in applications

Java API — `flydb-core` does not depend on any connection pool, logging framework, or JDBC driver; the caller manages the `DataSource`:

```java
Flydb flydb = Flydb.configure()
    .dataSource(dataSource)
    .databaseType("mysql") // explicit is recommended for family reuse or custom dialects
    .locations("classpath:db/migration")
    // .targetVersion("3")
    .load();

flydb.migrate();
```

Plain Java applications depend on `flydb-core`:

```xml
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-core</artifactId>
  <version>0.3.2</version>
</dependency>
```

Spring Boot applications pick the matching starter; it runs `migrate` during context initialization and aborts startup on failure:

```xml
<!-- Spring Boot 3.x / Java 17+ -->
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-spring-boot-3-starter</artifactId>
  <version>0.3.2</version>
</dependency>
<!-- Spring Boot 2.7 / Java 8 -->
<dependency>
  <groupId>io.github.zzxcoding</groupId>
  <artifactId>flydb-spring-boot-2-starter</artifactId>
  <version>0.3.2</version>
</dependency>
```

> The CLI is distributed through [GitHub Releases](https://github.com/zzxCoding/Flydb/releases). Starting with `v0.2.1`, all modules under the `io.github.zzxcoding` groupId are published to Maven Central (Java package names stay `com.flydb.*`); earlier releases must be built from source.

Java 8 applications use `flydb-spring-boot-2-starter` (Boot 2.7.18; [Spring states](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/) that 2.7.18 is the last open-source release of the Boot 2.x line, so new projects should prefer the Boot 3 starter). The starter reuses the application's primary `DataSource` by default; set `flydb.url/user/password` to migrate with a separate DDL account, and `flydb.enabled=false` to disable auto-configuration entirely. Runnable examples: [Boot 2](./examples/boot2-demo), [Boot 3](./examples/boot3-demo); see the [Spring Boot starter design](./docs/design/07-spring-boot-starter.md).

## Names and configuration

```text
V1__create_user.sql       # versioned migration, applied successfully once
V1.1__add_status.sql      # dotted version
R__refresh_user_view.sql  # rerun when its checksum changes
U1__create_user.sql       # undo for the last applied V1
```

> **Naming change:** `R<version>__...sql` is rejected with `FLYDB-2005` and the check cannot be disabled. Use `U<version>__...sql` for undo scripts and versionless `R__...sql` for repeatable migrations.

- The default location is `filesystem:db/migration`, scanned recursively; the config generated by `init` uses an absolute path so runs are independent of the working directory.
- Configuration precedence is `CLI args > FLYDB_* environment variables > flydb.conf > built-in defaults`; config files are resolved from `--config`, the current directory, then the installation's `conf/`; unknown `flydb.*` keys fail with a near-miss suggestion.
- SQL supports `${key}` placeholders passed with `-Dkey=value`; undefined placeholders fail before execution with the script line number.
- Exit codes: `0` success, `1` general error, `2` validation failure, `3` lock conflict or timeout, `4` configuration error, `5` user interruption.

```bash
bin/flydb migrate --target-version 3
bin/flydb migrate --start-version 2 --end-version 5
bin/flydb validate
bin/flydb baseline --baseline-version 5
bin/flydb repair
bin/flydb undo
bin/flydb clean --clean-disabled=false --force   # clean is disabled by default; double opt-in for non-interactive use
```

Version families, directory versions, path glob/regex filtering, and directory-version ordering are explicitly enabled advanced rules; no filtering bypasses validation or `out-of-order` protection. See the [configuration reference](./docs/reference/configuration.md) (in Chinese) for full patterns and safety constraints, the [command reference](./docs/reference/commands.md), and the [error-code reference](./docs/reference/errors.md). For organizing automation across multiple databases and multiple test/production environments, see the [multi-environment guide](./docs/getting-started/multi-environment.md) (in Chinese).

## Build from source

The full reactor, including the Boot 3 modules, is built with Java 17; the Boot 2 starter, Boot 2 example, core, and CLI retain Java 8 bytecode. If your shell switches JDKs through a function, run `jdk17` first:

```bash
./mvnw verify
```

The CLI distribution is generated at `flydb-cli/target/flydb-cli-0.3.2.zip`. The core module enforces an 80% JaCoCo line-coverage gate and zero non-test runtime dependencies via Maven Enforcer.

Local integration contracts default to MySQL 8 only; to run a specific CI dialect, set `-Pmysql`/`-Ppostgresql` and `-Dflydb.integration.database=<dialect>`. The full matrix runs in `.github/workflows/ci.yml`.

<details>
<summary>Pre-release checks (stage 8)</summary>

```bash
./scripts/check-bytecode.sh 52 \
  flydb-core/target/classes flydb-cli/target/classes \
  flydb-spring-boot-2-starter/target/classes examples/boot2-demo/target/classes
./scripts/check-bytecode.sh 61 \
  flydb-spring-boot-3-starter/target/classes examples/boot3-demo/target/classes
./mvnw -DskipTests deploy \
  -DaltDeploymentRepository=local::file:./target/staging
./scripts/check-release-artifacts.sh target/staging flydb-cli/target
```

</details>

## Contributing

Issues and PRs are welcome. See the [contributing guide](./CONTRIBUTING.md) for the full workflow, and run `./mvnw -B verify` before submitting. Report vulnerabilities privately through the process in the [security policy](./SECURITY.md), not in a public issue. Start with the [design overview](./docs/design/00-overview.md) for architecture and design documents.

## License

[Apache-2.0](./LICENSE). Flydb is distributed under the Apache License 2.0; users obtain JDBC drivers separately and must comply with vendor licensing and distribution terms. Release packages include [`NOTICE`](./NOTICE).
