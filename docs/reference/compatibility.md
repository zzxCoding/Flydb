# 兼容性矩阵

本文是 Flydb 模块、运行环境与数据库支持的公开兼容性参考。验证层级只代表当前验证证据，不代表厂商认证；未列出的组合视为未验证，不做推断。

## 模块与运行环境

Maven 坐标 groupId 统一为 `io.github.zzxcoding`（Central Portal 经 GitHub 验证的 namespace）。`v0.2.1` 起发布到 Maven Central；`0.2.0` 及更早版本仅有 GitHub Release 的 CLI 发行包，未发布到 Central。

| 模块 | 坐标 | Java 运行时 | 依赖框架 | 发布到 Central |
|---|---|---:|---|---|
| Core（Java API） | `io.github.zzxcoding:flydb-core` | 8+ | 无（零第三方运行时依赖） | 是 |
| CLI | `io.github.zzxcoding:flydb-cli` | 8+ | picocli | 是（jar；发行 ZIP 经 GitHub Release 分发） |
| Spring Boot 2 Starter | `io.github.zzxcoding:flydb-spring-boot-2-starter` | 8+ | Spring Boot 2.7.x（以 2.7.18 验证） | 是 |
| Spring Boot 3 Starter | `io.github.zzxcoding:flydb-spring-boot-3-starter` | 17+ | Spring Boot 3.x（以 3.5.16 验证） | 是 |
| Parent POM | `io.github.zzxcoding:flydb-parent` | — | — | 是 |
| examples、flydb-integration-tests | — | — | — | 否（示例与测试模块，不发布） |

约束与依据：

- core、CLI、Boot 2 starter 的字节码基线为 Java 8，构建时以 release 8 交叉编译校验；Boot 3 starter 为 Java 17。
- 构建整个 reactor 需要 JDK 17 与 Maven 3.6.3+；Java 8 模块可在 JDK 8 上单独构建（见 `.github/workflows/ci.yml`）。
- Spring Boot 2.7 是其系列最后一个开源支持版本，新项目应优先 Boot 3 starter。
- CLI 发行包为平台无关 ZIP（`bin/flydb`、`bin/flydb.bat`），要求 Java 8+。

## 数据库支持

方言标识即 `flydb.database-type` / `--database-type` 的取值。验证层级沿用 [README 数据库支持表](../../README.md#数据库支持)，分级含义见文末。

| 数据库 | 方言标识 | JDBC 驱动（获取方式） | 驱动类 | 驱动 Java 要求 | 当前验证层级 |
|---|---|---|---|---|---|
| MySQL | `mysql` | `com.mysql:mysql-connector-j` | `com.mysql.cj.jdbc.Driver` | 官方声明支持 JRE 8+ | 自动化兼容测试；CLI 发行包端到端验证 |
| PostgreSQL | `postgresql` | `org.postgresql:postgresql` 42.x | `org.postgresql.Driver` | 官方声明支持 JRE 8+ | 自动化兼容测试 |
| Oracle | `oracle` | Oracle 制品库 `ojdbc` | `oracle.jdbc.OracleDriver` | 见厂商文档 | 自动化契约测试；已在授权真实实例完成 validate、clean、migrate 端到端验证 |
| 达梦 DM8 | `dm` | 达梦安装介质或企业制品库 | `dm.jdbc.driver.DmDriver` | 见厂商文档 | 方言与驱动元数据契约测试；真实环境认证待补 |
| 人大金仓 KingbaseES | `kingbasees` | 人大金仓交付介质（`cn.com.kingbase:kingbase8`，选不带 `.jre` 后缀的 JDK8+ 版本） | `com.kingbase8.Driver` | 见厂商文档 | 方言与驱动元数据契约测试；真实环境认证待补 |
| openGauss | `opengauss` | `org.opengauss:opengauss-jdbc` | `org.opengauss.Driver` | 见厂商文档 | 方言与驱动元数据契约测试；真实环境认证待补 |
| OceanBase | `oceanbase` | `com.oceanbase:oceanbase-client` | `com.oceanbase.jdbc.Driver` | 官方声明基于 Java 8 开发 | Oracle 租户已在授权真实实例完成端到端验证；MySQL 租户为轻量兼容测试 |
| TiDB | `tidb` | `com.mysql:mysql-connector-j` | `com.mysql.cj.jdbc.Driver` | 同 MySQL 驱动 | 轻量兼容测试；真实环境覆盖持续补充 |
| 其他 JDBC 数据库 | 需自定义 | 由接入方提供 | 由接入方提供 | 由接入方确认 | 需实现 `DatabaseType` SPI 方言并自行验证 |

Flydb 不捆绑、不重新分发任何 JDBC 驱动；驱动许可证与获取渠道以厂商为准（见发行包 `drivers/README.md` 与[各数据库上手指南](../getting-started/README.md)）。

## 验证层级定义

| 层级 | 含义 |
|---|---|
| 方言与驱动元数据契约测试 | 无真实数据库实例，基于方言单元测试与驱动元数据契约的离线验证 |
| 自动化兼容测试 | CI 中以容器化真实数据库运行的契约测试（见 `.github/workflows/ci.yml` 的 dialect-contract 矩阵） |
| 轻量兼容测试 | 通过家族方言（MySQL/Oracle）在目标库上执行的有限自动化验证 |
| 授权真实实例端到端验证 | 在获授权的真实实例上完成 `validate`、`--dry-run migrate` 与无害迁移验证 |

新数据库描述为"已支持"前，必须在获授权实例上完成上述最高层级的验证（见 [AGENTS.md](../../AGENTS.md) 第 6 节）。

## 维护规则

- 本矩阵与 [README 数据库支持表](../../README.md#数据库支持)、CI 实际执行的测试矩阵保持一致；修改数据库支持范围时必须同步更新（[测试路线图](../design/08-testing-roadmap.md)要求两者一致）。
- 新增模块、变更 Java 基线或调整发布范围（`docs/design/01-modules.md` §1）时同步更新模块表。
- Maven Central 实际发布某版本后，才可在本矩阵与 README 中将其描述为可获取；发布前不得宣称。
