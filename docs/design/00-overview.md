# Flydb 0.2 设计总览

> 设计文档目录：本文（总览）| [01 模块划分](01-modules.md) | [02 领域模型与 API](02-domain-api.md) | [03 数据库方言层](03-dialects.md) | [04 解析器/锁/事务](04-parser-lock-tx.md) | [05 命令语义](05-commands.md) | [06 配置与 CLI](06-config-cli.md) | [07 Spring Boot Starter](07-spring-boot-starter.md) | [08 测试与路线图](08-testing-roadmap.md) | [09 实施交接计划](09-implementation-plan.md) | [10 机器契约](10-machine-contract.md) | [11 Plan Artifact](11-plan-artifact.md)

## 1. 产品定位

**Flydb 是一个基于 JDBC 的数据库 Schema 版本化迁移工具，面向所有提供 JDBC 驱动的数据库。它内置支持 MySQL、PostgreSQL 等主流数据库，以国产信创数据库的一等支持为特色，并可通过方言 SPI 扩展接入小众 JDBC 数据库。**

它借鉴 Flyway 的核心思想（版本化 SQL 脚本 + schema history 表 + checksum 校验），在提供通用 JDBC 迁移能力的基础上，为信创场景做了针对性设计：

- **面向各类 JDBC 数据库**：MySQL、PostgreSQL、Oracle 等主流数据库开箱即用；其他提供 JDBC 驱动的数据库可通过 `DatabaseType` SPI 和方言实现接入。
- **信创数据库一等公民**：达梦 DM8、人大金仓 KingbaseES、openGauss 与 MySQL/PostgreSQL 同级支持，而不是"社区插件"待遇。
- **Java 8 字节码基线**：信创环境（东方通/宝兰德中间件、国产化改造项目）大量停留在 JDK 8。Flyway 10+ 强制 Java 17，把这批用户挡在门外；Flydb 的核心库与 CLI 承诺 Java 8 可运行。
- **离线友好**：CLI 通过 `drivers/` 目录动态加载 JDBC 驱动，不假设能访问公网 Maven 仓库——达梦、金仓的驱动通常经企业内部制品库分发。
- **中文体验**：CLI 输出、错误消息、修复建议均提供中文（同时保留英文），错误码稳定可检索。

### 1.1 与 Flyway 的差异化（精确表述）

Flyway Community 采用 Apache 2.0 许可，且其官方支持矩阵**已包含 OceanBase 与 TiDB**（可信度：高，Redgate 官方支持列表）。因此 Flydb 的差异化必须精确表述为：

| 维度 | Flyway Community | Flydb |
|---|---|---|
| openGauss / KingbaseES / 达梦 DM8 | ❌ 不支持 | ✅ MVP 支持 |
| OceanBase / TiDB | ✅ 支持 | ✅ 支持（提供更贴近国内环境的开箱体验） |
| Java 运行时要求 | 10+ 版本要求 Java 17 | 核心库与 CLI 兼容 Java 8 |
| CLI 中文输出 / 中文错误建议 | ❌ | ✅ |
| 离线驱动分发（drivers/ 目录） | 部分 | ✅ 核心设计目标 |
| 二期扩展 | — | 神通 Oscar、GBase、瀚高（SPI 独立 jar 接入） |

**不做的事**（明确边界）：不做 schema 对比/state-based migration（Flyway 付费能力），不做数据同步/CDC，MVP 不做 REST 服务形态（旧原型的 Web 服务 + curl 脚本形态被移除，理由见 §3）。

## 2. 旧原型的教训与新设计的修复责任

旧原型（993 行，Spring Boot 2.7 Web 服务）存在以下正确性缺陷。**新设计的每一条修复都有明确的责任模块**，这是评审与实施验收的第一道标准：

| # | 旧原型缺陷 | 后果 | 新设计修复点 | 责任模块（文档） |
|---|---|---|---|---|
| 1 | 迁移脚本按 `Files.walk` 文件系统遍历顺序执行，无排序 | 迁移乱序执行，schema 状态不可预测 | Resolver 输出统一按 `MigrationVersion` 排序 | [02 §5](02-domain-api.md)、[05 §1](05-commands.md) |
| 2 | checksum 用 `String.hashCode()`（跨 JVM 不稳定）且只写不查 | 脚本被篡改无法感知，validate 语义不存在 | CRC32（行尾归一化）+ `validate()`/`validateOnMigrate` 强制比对 | [02 §7](02-domain-api.md)、[05 §3](05-commands.md) |
| 3 | 失败迁移记录写入历史表但查询"当前版本"不过滤 `success` | 失败版本被误判为当前版本，后续迁移被错误跳过 | 历史仓储查询显式 `WHERE success = true`；FAILED 记录阻塞 migrate、走 repair 流程 | [02 §6](02-domain-api.md)、[05 §5](05-commands.md) |
| 4 | 整个 SQL 文件塞进单条 `PreparedStatement.execute()` | 多语句脚本在多数驱动下直接失败 | 字符级状态机把脚本切分为语句列表，逐条执行 | [04 §1](04-parser-lock-tx.md) |
| 5 | 同一数据库并发迁移无任何锁保护 | 两个进程同时 migrate 产生竞态、重复执行 | 每个方言强制提供 `MigrationLock`，migrate/baseline/repair/clean 全程持锁 | [04 §2](04-parser-lock-tx.md) |
| 6 | 版本号仅支持纯整数（`Integer.parseInt`） | 不支持 `1.2`、`20260812.1`、`20260327-b06.4` 等常用版本风格 | `MigrationVersion` 支持数字/字母 token，自然排序且数字使用 BigInteger | [02 §3](02-domain-api.md) |

此外的工程性问题（无测试、God Class、硬编码 root/root 凭据、SQL 字符串拼接、配置项声明了但代码不读）在新设计中分别由测试策略（[08](08-testing-roadmap.md)）、模块划分（[01](01-modules.md)）、配置体系（[06](06-config-cli.md)）系统性解决。

## 3. 产品形态

三个交付物 + 一个测试模块（详见 [01 模块划分](01-modules.md)）：

```
                     ┌─────────────────────┐
                     │      flydb-cli      │  独立可执行 zip（picocli）
                     │  migrate/info/...   │  drivers/ 目录动态加载驱动
                     └──────────┬──────────┘
                                │
┌───────────────────┐  ┌────────▼──────────┐  ┌───────────────────────────┐
│ flydb-spring-boot │  │    flydb-core     │  │ flydb-spring-boot-3-starter│
│    -2-starter     ├─►│  纯 Java 8 API 库 │◄─┤     (Boot 3 / Java 17)    │
│ (Boot 2.7/Java 8) │  │  零第三方运行时依赖│  └───────────────────────────┘
└───────────────────┘  └────────┬──────────┘
                                │ ServiceLoader SPI
                       ┌────────▼──────────┐
                       │  数据库方言（内置） │  PG 系 / MySQL 系 / Oracle 系
                       │  + 二期外部方言 jar │  三家族继承，内置方言
                       └───────────────────┘
```

**砍掉 REST 服务形态的理由**：旧原型要求先启动常驻 Web 服务再用 curl 触发迁移。这与迁移工具"一次性执行、执行完退出、退出码给 CI 用"的语义不符，且常驻的无鉴权迁移 API 本身是安全隐患。多库批量编排的需求（旧原型多线程并发迁移多库的想法）在三期以专门的编排命令承接（见 [08 §4 路线图](08-testing-roadmap.md)）。

### 3.1 flydb-core 内部分层

```
┌────────────────────────────────────────────────────────┐
│ api        Flydb 门面 / FlydbConfiguration（不可变）      │
├────────────────────────────────────────────────────────┤
│ command    MigrateCommand / InfoCommand / ...（每命令一类）│
├────────────────┬───────────────────┬───────────────────┤
│ resolver       │ executor          │ history            │
│ 发现+排序+校验  │ 语句切分状态机+执行 │ schema history 读写 │
├────────────────┴───────────────────┴───────────────────┤
│ database   DatabaseType SPI / Database 抽象 / 三家族方言  │
│            MigrationLock（并发锁）                        │
└────────────────────────────────────────────────────────┘
```

## 4. 迁移脚本命名规范

沿用 Flyway 习惯的命名规范（用户心智成本最低），MVP 支持三类前缀：

| 前缀 | 形式 | 语义 | 示例 |
|---|---|---|---|
| `V` | `V{version}__{description}.sql` | 版本化迁移：按版本升序执行一次，checksum 受 validate 保护 | `V1.2__add_user_index.sql` |
| `R` | `R__{description}.sql`（**不带版本号**） | 可重复迁移：内容 checksum 变化时重新执行，在所有 pending 版本化迁移之后按描述排序执行。典型用途：视图、存储过程、权限脚本 | `R__user_summary_view.sql` |
| `U` | `U{version}__{description}.sql` | 撤销迁移：撤销对应版本的 `V` 迁移，MVP 仅支持撤销最近一次（见 [05 §7](05-commands.md)） | `U1.2__add_user_index.sql` |

版本号：以数字开头，字母数字 token 可用 `.`、`_`、`-` 分隔（`1`、`1.2`、`20260812.1`、`20260327-b06.4`）；版本与描述的默认分隔符是 `__`（两个下划线）。前缀/分隔符/后缀均可通过配置修改（见 [06 §2](06-config-cli.md)）。

### 4.1 ⚠️ 破坏性变更：`R` 前缀语义变化

**旧原型中 `R{version}__{desc}.sql`（如 `R1__rollback_v1.sql`）表示"回退脚本"，带版本号。新设计对齐 Flyway 语义，`R` 表示"可重复迁移"，不带版本号；回退能力由 `U` 前缀承接。**

这是不兼容变更，且误用后果严重：若把旧的回退脚本（可能含 `DROP TABLE`）当作可重复迁移反复执行，会造成数据灾难。因此：

- Resolver 扫描到 `R\d+__` 形式的旧式命名时，**直接报错阻断**（错误码 `FLYDB-2005`），给出重命名指引（回退脚本 → `U{version}__`，可重复脚本 → `R__`），绝不静默兼容。
- 该检查是 Resolver 的固定行为，不提供关闭开关。

## 5. schema history 表（`flydb_schema_history`）

保留旧原型/Flyway 风格的表设计思路，修正其错误（`installed_rank` 不再与版本号混用，而是独立单调递增的记账序号）：

| 列 | 类型（逻辑） | 说明 |
|---|---|---|
| `installed_rank` | INT，主键 | 应用顺序序号，单调递增，由历史仓储在插入时计算（当前最大值+1） |
| `version` | VARCHAR(50)，可空 | 版本号原文；可重复迁移为 NULL |
| `description` | VARCHAR(200) | 描述 |
| `type` | VARCHAR(20) | `SQL` / `JDBC`（Java 迁移）/ `BASELINE` / `UNDO_SQL` |
| `script` | VARCHAR(1000) | 脚本相对路径或 Java 类全限定名 |
| `checksum` | INT，可空 | CRC32（见 [02 §7](02-domain-api.md)） |
| `installed_by` | VARCHAR(100) | 执行时的数据库用户 |
| `installed_on` | TIMESTAMP | 记录写入时间，默认数据库当前时间 |
| `execution_time` | INT | 执行耗时（毫秒） |
| `success` | BOOLEAN（逻辑） | 是否成功；各方言映射为本地布尔/数值类型 |

各方言的具体建表 DDL 由 `Database.schemaHistoryDdl()` 提供（见 [03 §3](03-dialects.md)）。表名默认 `flydb_schema_history`，可配置。

## 6. 设计总则

1. **不可变优先**：`FlydbConfiguration`、`MigrationVersion`、`ResolvedMigration`、`AppliedMigration`、`MigrationInfo`、各 `*Result` 全部是不可变值对象；只有 Builder 是可变中间态。
2. **Java 8 是产品承诺**：flydb-core / flydb-cli 的一切技术选型先过"JDK 8 能跑吗"这一关；测试与集成测试模块不受此约束。
3. **方言是护城河**：家族继承 + ServiceLoader SPI 必须稳固到"二期新增神通/GBase/瀚高时零改动 core"。
4. **core 零第三方运行时依赖**：不依赖任何 JDBC 驱动（连 optional/provided 都不声明）、不依赖日志门面之外的库（日志方案见 [01 §4](01-modules.md)）、不依赖 YAML 库。
5. **防呆优先于功能**：`clean` 默认禁用；旧式 `R{n}__` 命名报错阻断；失败迁移阻塞后续 migrate 需显式 repair；密码不进日志。
6. **遵循仓库编码规范**：文件 <800 行（典型 200~400）、函数 <50 行、无硬编码（常量/配置化）、TDD、行覆盖率 ≥80%。

## 7. 阅读路径建议

- **评审产品方向**：本文 → [06 配置与 CLI](06-config-cli.md) → [08 测试与路线图](08-testing-roadmap.md)（末尾开放问题需拍板）。
- **评审技术架构**：本文 §2 对照表 → [02 领域模型](02-domain-api.md) → [03 方言层](03-dialects.md) → [04 解析器/锁/事务](04-parser-lock-tx.md) → [05 命令语义](05-commands.md)。
- **执行实施**：[09 实施交接计划](09-implementation-plan.md)（自包含，可冷启动）。
