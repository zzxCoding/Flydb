# 08 测试策略、CI、路线图与开放问题

> [← 07 Spring Boot Starter](07-spring-boot-starter.md) | [返回总览](00-overview.md) | 下一篇：[09 实施交接计划](09-implementation-plan.md)

## 1. 单元测试（无数据库、无 Docker，flydb-core 内）

TDD 强制（先写测试再实现，见 [09 §2](09-implementation-plan.md)），JaCoCo 行覆盖率 ≥80% 作为构建门禁。核心清单：

| 测试类 | 覆盖内容 |
|---|---|
| `MigrationVersionTest` | 表驱动比较：`1.2` vs `1.2.0` 相等、多段长短不一、BigInteger 大数版本（`20260812.1`）、字母连字符版本（`20260327-b06.4`）、非法输入报 FLYDB-2001、equals/hashCode/compareTo 三者一致性 |
| `SqlScriptLexerTest` | 按家族 fixture：`--`/`/* */`/`#` 注释、`''` 与 `\'` 转义、`$$`/`$tag$` dollar-quoting、PL/SQL 块（`CREATE TRIGGER`、裸 `DECLARE`/`BEGIN`、`/` 终止）、`DELIMITER` 指令存储过程、CRLF 文件、最后一条语句无分号、语句起始行号正确性、Unicode 内容 |
| `ChecksumCalculatorTest` | CRLF→LF 归一化前后一致、UTF-8 BOM 剥离、同内容跨平台稳定 |
| `MigrationInfoStateTest` | [02 §6](02-domain-api.md) 状态推导真值表逐行覆盖（含 UNDONE、FUTURE、OUTDATED 分支） |
| `PendingCalculatorTest` | FAILED 阻断、baseline 过滤、outOfOrder 两态、可重复迁移排序、UNDONE 重入 |
| `SqlMigrationResolverTest` | 命名解析边界：重复版本报错、旧式 `R\d+__` 阻断（FLYDB-2005）、前缀/分隔符/后缀可配置、classpath 与 filesystem 两种 location |
| `ConfigLoaderTest` | 四层优先级合并、`${env:VAR}` 解析、未知键报错（FLYDB-4001）、环境变量映射规则 |
| `PlaceholderReplacerTest` | 替换、`$${` 转义、未定义占位符报错（含行号）、内置 `flydb:` 变量 |
| `DatabaseTypeRegistryTest` | URL 前缀优先于产品名（mock Connection 模拟达梦 compatibleMode=oracle 伪装场景）、TiDB 与 MySQL 歧义消解、显式 databaseType 跳过探测、零候选报错 |

## 2. 集成测试（flydb-integration-tests，契约测试模式）

### 2.1 `DatabaseTestSupport` 抽象

```java
public interface DatabaseTestSupport extends AutoCloseable {
    DataSource dataSource();
    String jdbcUrl();
    void resetSchema();     // 用例间隔离
}
```

同一套**契约测试**（`MigrateContractTest`、`LockContractTest`、`FailureRecoveryContractTest`、`CleanContractTest`...）对每个方言跑一遍，由 JUnit 5 扩展按环境选择实现：

1. 设置了 `FLYDB_TEST_<DB>_URL/_USER/_PASSWORD` 环境变量 → **外部真实实例**模式（包括原生 Oracle）；
2. 本地默认只启动已有的 MySQL 8 Testcontainers，以显式方言配置运行 MySQL 家族兼容契约；设置 `-Dflydb.integration.database=postgresql` 时才启动 PostgreSQL 16，避免普通开发环境拉取两套镜像；
3. TiDB、OceanBase、openGauss 等专用大镜像不在本地默认拉取范围，真实产品验证放到显式 CI job 或外部实例；
4. 达梦、金仓、Oracle 等授权环境不可用时，真实实例用例 **Disabled 并输出明确原因**（不让整体构建失败）。

兼容族契约用于验证 Flydb 的迁移、历史表、锁和 SQL 家族实现，不等价于真实产品证明。例如 TiDB 方言在 MySQL 上跑通，只能证明 MySQL 家族公共路径；TiDB 异步 DDL、产品探测返回值等差异仍需真实 TiDB 环境验证。此分层既控制本地磁盘与启动成本，也避免把兼容数据库上的结果包装成真实产品结论。

### 2.2 各方言测试方案矩阵

| 数据库 | 本地默认验证 | 真实产品验证 | CI 归属 |
|---|---|---|---|
| MySQL / PostgreSQL | 官方 Testcontainers 模块 + 官方镜像 | 同一容器即真实产品 | 公共 CI |
| Oracle | Oracle 家族单元契约 | `FLYDB_TEST_ORACLE_URL/_USER/_PASSWORD` | 自建 Runner/外部实例；公共 CI 跳过 |
| TiDB | MySQL 8 兼容族契约 | 外部 TiDB 实例或显式专用 CI job | 专用 job 未启用前只标注“兼容验证” |
| OceanBase-MySQL | MySQL 8 兼容族契约 + 探测代理 | 外部 OB-MySQL 租户或显式专用 CI job | 同上 |
| openGauss | PostgreSQL 16 兼容族契约 | 外部 openGauss 实例或显式专用 CI job | 同上 |
| KingbaseES | PostgreSQL 16 兼容族契约 | `FLYDB_TEST_KINGBASE_URL/_USER/_PASSWORD` | 自建 Runner/外部实例；公共 CI 跳过 |
| 达梦 DM8 | Oracle 家族单元契约 | `FLYDB_TEST_DM_URL/_USER/_PASSWORD` | 自建 Runner/外部实例；公共 CI 跳过 |
| OceanBase-Oracle | Oracle 家族锁表行锁单元契约 | 企业版 Oracle 租户 | 无法自动化；方言标注**实验性** |

### 2.3 CI（GitHub Actions）

- 默认公共 Runner 跑 MySQL/PG；TiDB/OB-CE/openGauss 只有在显式配置专用 job 后才拉取对应镜像。单测与覆盖率门禁在所有 PR 上强制。
- 达梦/金仓/Oracle job 打 `self-hosted, licensed-db` 标签，用 `if: vars.RUN_LICENSED_DB_TESTS == 'true'` 门禁——外部贡献者 PR 不因缺企业凭据而失败，主分支 push 才跑全量。
- 国内自建 Runner 建议配置镜像加速并对测试镜像做 digest 锁定（信创网络环境拉公网镜像不稳定）。
- 驱动字节码校验步骤：对 core/cli 产物跑 `jdeps`/`javap` 断言 class 版本 ≤52（Java 8），对引入的达梦/金仓/openGauss 驱动同样校验并在升级时报警（[01 §5](01-modules.md)）。

当前工作流落在 `.github/workflows/ci.yml`：单元/覆盖率/发布演练、Java 8 兼容性、MySQL/PostgreSQL/TiDB/OceanBase-MySQL/openGauss 五项矩阵，以及按 `RUN_LICENSED_DB_TESTS` 开关启用的达梦/金仓/Oracle 授权数据库 self-hosted gate。集成测试通过 `flydb.integration.database` 选择器做到一项只启动一套数据库家族；默认本地值为 `mysql`。

## 3. 方言成熟度分级（对外承诺口径）

| 级别 | 含义 | MVP 归属 |
|---|---|---|
| **稳定** | 真实产品在公共 CI 每次提交自动验证 | MySQL、PostgreSQL |
| **兼容验证** | 在同协议/同家族数据库上通过完整迁移契约，产品专有行为尚待真实实例验证 | TiDB、OceanBase-MySQL、openGauss、KingbaseES |
| **验证** | 自建 Runner/外部实例门禁验证 | Oracle、达梦 DM8；KingbaseES 在真实实例门禁全绿后由“兼容验证”升级至此 |
| **实验性** | 无法自动化验证，社区反馈驱动 | OceanBase-Oracle |

README 的支持矩阵必须如实标注该分级——旧原型"README 宣传与代码脱节"的教训不再重演。

## 4. 版本路线图

### MVP（本设计范围）
flydb-core（三家族内置方言）+ flydb-cli + 双 starter + 契约测试矩阵。`clean` 限定表/视图/序列；`undo` 仅撤销最近一次。

### 二期
- 神通 Oscar、GBase、瀚高以**独立方言 jar** 接入（验证 SPI 真解耦，成本模型见 [03 §6](03-dialects.md)）。
- 原生命名锁优化（MySQL `GET_LOCK`、达梦锁方案调研）替换锁表方案（带运行时能力探测）。
- `flydb-maven-plugin`（Mojo 包装各命令，"迁移即构建步骤"）。
- `clean` 补齐存储过程/触发器/自定义类型；`validate` 增加 `ignoreMissingMigrations` 等宽松开关；YAML 配置可选模块。

### 三期
- **多库编排批量下发**：同一套迁移脚本 fan-out 到多个物理库/租户（多租户 SaaS、分库分表场景）——旧原型"多数据库并发执行"想法的正规化，需要新设计跨库执行报告聚合、部分失败处置策略。
- **GraalVM Native CLI 评估**：与 `drivers/` 动态加载设计直接冲突（封闭世界静态分析 vs 运行时反射加载任意驱动）。若做，需改为"预置驱动组合分发多个二进制"——产品形态级取舍，留待用户反馈驱动决策。

## 5. 风险与开放问题（评审需拍板）

| # | 问题 | 影响 | 建议决策 |
|---|---|---|---|
| 1 | **达梦/金仓无公开匿名 Docker 镜像**：CI 覆盖依赖企业自建私有镜像仓库或长期可用的授权实例 | 这两个方言无法在公共 CI 验证，需要组织层面的资源投入（授权/运维） | 确认能否拿到可长期用于 CI 的达梦/金仓环境；拿不到则两方言按"验证"级发布并明确标注 |
| 2 | **OceanBase-Oracle 仅企业版可建租户** | 该方言无法自动化测试 | 接受"实验性"标注（本设计已内置该决策），或从 MVP 移除该方言 |
| 3 | **KingbaseES `pg_advisory_lock` 与 `getDatabaseProductName()` 未经官方确认**（可信度：低） | 探测与锁的实现细节需实测后定稿 | 实施阶段第一时间用真实实例验证（[09 §5](09-implementation-plan.md) 实测清单第 2/3 项），设计已预置降级路径 |
| 4 | **TiDB 异步在线 DDL**：大 DDL 的耗时统计/超时/会话断开语义与普通 MySQL 不同 | migrate 长时间等待或误判 | MVP 文档记录为已知限制；实施阶段补大 DDL 实测用例 |
| 5 | **MySQL Connector/J GPL+FOSS 例外**与"驱动不内置"分发模型 | 法律合规 | 正式发布前请法务/合规确认该分发模型（设计已按最稳妥姿态：任何模块不捆绑驱动） |
| 6 | **锁策略简化**（MySQL/Oracle 系 MVP 统一锁表，不做原生锁探测） | 锁表方案多一次建表；PG 系无此问题 | 已内置该决策（[04 §2.2](04-parser-lock-tx.md)），评审可推翻 |
| 7 | **openGauss 用户误用 PG 驱动连接**的残余风险：阶段二兜底探测 + 文档引导，无法完全消除 | 误判为 PostgreSQL 方言运行，行为可能有偏差 | 接受残余风险等级；info/migrate 输出中始终显示探测到的方言名，便于用户发现 |

## 6. 验收标准汇总（MVP Done 的定义）

1. [00 §2](00-overview.md) 六大缺陷修复对照表全部有对应测试证明；
2. MySQL/PostgreSQL 真实产品契约与四个新增兼容方言的家族契约全绿；其他方言只有在真实实例门禁通过后才能升级成熟度；
3. 单测覆盖率 ≥80%（JaCoCo 门禁）；
4. CLI 发行 zip 在纯 JDK 8 环境完成 init → migrate → info → validate 全流程（人工验收脚本见 [09 §6](09-implementation-plan.md)）；
5. starter 在 Boot 2.7（JDK 8）与 Boot 3（JDK 17）示例工程各跑通一次启动迁移；
6. README 支持矩阵与实际实现/CI 状态一致。

阶段 8 实施时，所有发布检查脚本都必须在仓库内可直接运行；不得把“兼容家族契约”描述成真实产品认证。
