# Changelog

Flydb 的重要变更记录在本文件中。版本遵循语义化版本；正式发行包与发布说明见
[GitHub Releases](https://github.com/zzxCoding/Flydb/releases)。

## [0.2.1] - 2026-08-15

### 变更

- Maven 坐标 groupId 由 `com.flydb` 调整为 `io.github.zzxcoding`（Sonatype Central Portal 经 GitHub 验证的 namespace）；Java 包名保持 `com.flydb.*` 不变。`0.2.0` 及更早版本未发布到任何 Maven 仓库，无迁移影响。

### 新增

- Maven Central 发布链路：根 POM 新增 `central` 发布 profile（GPG 签名 + central-publishing-maven-plugin），tag 触发的 release workflow（`.github/workflows/release.yml`）一次完成全量 verify、签名、发布 Central 与 GitHub Release 附件；发布范围为 parent、core、CLI（jar）、两个 starter，examples 与 integration-tests 不发布。本版本即首个经该链路发布到 Maven Central 的版本。
- 兼容性矩阵参考文档（`docs/reference/compatibility.md`）：模块与 Java/Spring Boot 运行环境、数据库方言与驱动、验证层级。
- `scripts/check-release-artifacts.sh` 新增 `--signatures` 模式，发布前校验每个产物均有 GPG 签名且验证通过。

## [0.2.0] - 2026-08-14

首个正式公开版本，提供可用于本地开发、CI 和应用启动阶段的 Schema 迁移运行时。

### 新增

- Java 8 零第三方运行时依赖的迁移内核，以及 Java 8 CLI。
- `migrate`、`info`、`validate`、`baseline`、`repair`、`undo`、`clean` 和 `version` 命令。
- MySQL、PostgreSQL、Oracle、达梦 DM8、KingbaseES、openGauss、OceanBase、TiDB 方言或兼容家族。
- Spring Boot 2.7 与 Spring Boot 3 starter。
- 递归迁移发现、精确/范围/版本族选择、目录版本、glob/regex 路径过滤。
- 外置 JDBC 驱动、Maven 私服解析、离线模式及 `DatabaseType` SPI。
- 发行包内置版本匹配的文档、`AGENTS.md` 与 `flydb-cli` Agent Skill。

### 安全与可靠性

- 并发迁移锁、DDL 事务差异、失败记录阻断与恢复。
- checksum 校验、`migrate`/`undo` dry-run、稳定退出码与错误码。
- `clean` 默认禁用并要求双重确认；密码支持环境变量和密码文件。

### 当前边界

- GitHub Release 提供 CLI ZIP；Maven Central 和包管理器分发尚未开放。
- Flydb 不自动把任意厂商 SQL 转换为其他数据库语法。
- 达梦、KingbaseES、openGauss 的公开证据为方言和驱动元数据契约测试，真实环境认证仍待补充。

[0.2.1]: https://github.com/zzxCoding/Flydb/releases/tag/v0.2.1
[0.2.0]: https://github.com/zzxCoding/Flydb/releases/tag/v0.2.0
