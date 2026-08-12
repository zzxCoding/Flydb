# 03 数据库方言层设计

> [← 02 领域模型](02-domain-api.md) | [返回总览](00-overview.md) | 下一篇：[04 解析器/锁/事务](04-parser-lock-tx.md)

方言层是 Flydb 的核心价值所在。设计目标：8 个 MVP 方言按三家族继承实现；二期新增方言（神通/GBase/瀚高）零改动 core。

## 1. `DatabaseType` SPI：两阶段探测 + 显式覆盖

```java
public interface DatabaseType {
    String name();                     // 稳定标识，如 "opengauss"（配置 databaseType 时使用）
    int priority();                    // 越大越优先；解决 URL 前缀重叠歧义（TiDB > MySQL）
    boolean handlesUrl(String jdbcUrl);                                   // 阶段一：连接前
    boolean handlesConnection(Connection connection) throws SQLException; // 阶段二：连接后
    Database createDatabase(Connection connection, FlydbConfiguration cfg) throws SQLException;
}
```

`DatabaseTypeRegistry` 通过 `ServiceLoader<DatabaseType>` 加载所有实现（内置 + 外部 jar），探测流程：

1. 若配置了 `databaseType`，按 `name()` 直接命中，**跳过全部探测**（逃生舱）。
2. 阶段一：以 `handlesUrl()` 过滤候选集（URL 前缀判定）。
3. 阶段二：对候选按 `priority()` 降序逐个调 `handlesConnection()` 确认。
4. 零候选 → `FLYDB-1002`（提示支持列表 + 建议显式指定）；确认后多候选歧义 → 同样报错而非猜测。

### 1.1 为什么 URL 前缀优先于产品名探测

实测坑（可信度：中高，多篇独立技术文章印证）：**达梦 JDBC URL 带 `compatibleMode=oracle` 参数时，`DatabaseMetaData.getDatabaseProductName()` 返回 "Oracle" 而非 "DM DBMS"**。即产品名探测在特定参数下会被"伪装"欺骗，而 URL 前缀（`jdbc:dm://`）无论加什么参数都不变。

因此设计原则：**阶段一（URL）是权威判定，阶段二只在阶段一存在歧义（多候选共用前缀）时做二次区分，绝不允许阶段二推翻阶段一的结论**。实施阶段必须为"达梦 + compatibleMode=oracle"场景写回归测试，防止重构破坏该优先级（见 [09 §5](09-implementation-plan.md)）。

### 1.2 探测线索总表

| 方言 | URL 前缀 | 阶段二线索 | 已知风险 |
|---|---|---|---|
| PostgreSQL | `jdbc:postgresql://` | `SELECT version()` 不含 openGauss/Kingbase 特征 | 该前缀可能实际连着 openGauss（见下行） |
| openGauss | `jdbc:opengauss://`（专用驱动）；兼容 `jdbc:postgresql://` | `SELECT version()` 返回串含 `openGauss` | **高**：用户用 PG 驱动 + PG 前缀连 openGauss 时，仅靠阶段二版本串兜底识别；文档强烈建议使用专用驱动与 URL |
| KingbaseES | `jdbc:kingbase8://` | `getDatabaseProductName()` 实际返回值**待实测**（可信度：低）；备选 `SELECT version()` 含 `KingbaseES` | 前缀唯一，风险低；阶段二逻辑实施时补实测 |
| MySQL | `jdbc:mysql://` | 产品名 `MySQL` 且非 TiDB | 与 TiDB 共用前缀 |
| TiDB | `jdbc:mysql://`（协议兼容） | `SELECT tidb_version()` 成功即 TiDB（priority 高于 MySQL，先试） | 探测查询失败的开销一次、可接受 |
| OceanBase | `jdbc:oceanbase://` | `SHOW VARIABLES LIKE 'ob_compatibility_mode'` → `mysql` / `oracle`，据此选择两个 Database 实现之一 | 同一前缀两种模式，`OceanBaseDatabaseType.createDatabase()` 内部分派 |
| 达梦 DM8 | `jdbc:dm://` | 正常返回 `DM DBMS`；带 `compatibleMode=oracle` 时返回 `Oracle`（**不可信**） | 见 §1.1，以 URL 为准 |

## 2. `Database` 抽象

一个 `Database` 实例绑定一条连接会话，封装方言差异：

```java
public interface Database extends AutoCloseable {
    String name();                                   // 展示名，如 "达梦 DM8"
    boolean supportsDdlTransactions();               // 决定 migrate 失败处理策略（04 §3）
    String quote(String identifier);                 // 标识符引用（含转义）
    String currentSchema() throws SQLException;
    String currentUser() throws SQLException;        // 写入 installed_by
    SchemaHistoryDdl schemaHistoryDdl(String table); // 历史表 + 锁表建表 DDL
    SqlStatementBuilderConfig statementBuilderConfig(); // 语句切分器配置（04 §1）
    MigrationLock createLock(FlydbConfiguration cfg);   // 并发锁（04 §2）
    CleanStrategy cleanStrategy();                   // clean 实现（05 §6）
}
```

## 3. 三家族基类

### 3.1 `PostgreSQLFamilyDatabase` ← PostgreSQL / KingbaseES / openGauss

| 特性 | 家族默认值 |
|---|---|
| `supportsDdlTransactions()` | `true`（DDL 可回滚，migrate 失败自愈，见 [04 §3](04-parser-lock-tx.md)） |
| 标识符引用 | 双引号，内部 `"` 转义为 `""` |
| 语句切分 | 支持 `$$`/`$tag$` dollar-quoting；标准 `--`、`/* */` 注释 |
| 锁 | `pg_advisory_lock`（会话级，独立连接持有，见 [04 §2.1](04-parser-lock-tx.md)） |
| 历史表 DDL | `CREATE TABLE IF NOT EXISTS`；`success` 用 `BOOLEAN`；`installed_on TIMESTAMP DEFAULT now()` |
| currentSchema | `SELECT current_schema()` |

### 3.2 `MySQLFamilyDatabase` ← MySQL / TiDB / OceanBase-MySQL

| 特性 | 家族默认值 |
|---|---|
| `supportsDdlTransactions()` | `false`（DDL 隐式提交） |
| 标识符引用 | 反引号 |
| 语句切分 | 支持 `DELIMITER` 指令、反斜杠字符串转义、`#` 行注释（MySQL 特有） |
| 锁 | 通用锁表方案（MVP 决策，见 [04 §2.2](04-parser-lock-tx.md)；`GET_LOCK` 优化推迟二期） |
| 历史表 DDL | `CREATE TABLE IF NOT EXISTS`；`success` 用 `TINYINT(1)`；引擎默认 InnoDB |
| currentSchema | `SELECT DATABASE()` |

### 3.3 `OracleFamilyDatabase` ← 达梦 DM8 / OceanBase-Oracle

| 特性 | 家族默认值 |
|---|---|
| `supportsDdlTransactions()` | `false` |
| 标识符引用 | 双引号（引用即大小写敏感） |
| 语句切分 | 识别 PL/SQL 块（`CREATE PROCEDURE/FUNCTION/TRIGGER/PACKAGE/TYPE`、裸 `DECLARE`/`BEGIN`），块终止符为独占一行的 `/` |
| 锁 | 通用锁表方案（家族默认）；OceanBase-Oracle 覆写为 `DBMS_LOCK` |
| 历史表 DDL | 无 `IF NOT EXISTS` → "先查系统目录后建 + 建表异常兜底"的幂等策略；`success` 用 `NUMBER(1)`；`installed_on TIMESTAMP DEFAULT SYSTIMESTAMP` |
| currentSchema | 查询会话当前 schema（达梦：`SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual`，实施时确认） |

> MVP 不包含 Oracle 官方数据库的具体实现，但家族基类的能力边界按"Oracle 兼容语义"设计，二期加入 Oracle 方言时只补一个子类。

## 4. 8 个具体方言的覆写点清单

评审此清单 = 确认差异覆盖完整性。实施时每个覆写点都应有对应集成测试用例。

| 方言 | 相对家族基类必须覆写/确认的点 |
|---|---|
| **PostgreSQL** | 基准实现，无覆写 |
| **KingbaseES** | ① `pg_advisory_lock` 可用性**待实测**（可信度：低）——若不可用，`createLock()` 降级为通用锁表并在结果 warnings 中提示；② 阶段二探测串实测；③ 系统目录兼容性（金仓有 `sys_` 前缀系统表习惯，确认 `information_schema` 查询路径可用）；④ 驱动多变体（`.jre6/.jre7/无后缀`）不影响本层，但写入 drivers/README 选型提示 |
| **openGauss** | ① `pg_advisory_lock` 已确认保留（可信度：高，openGauss 官方文档 advisory-lock-functions）；② 用户误用 PG 驱动连接时的阶段二兜底识别；③ 默认加密认证方式与旧 PG 驱动不兼容的场景写入文档（驱动选型提示） |
| **MySQL** | 基准实现，无覆写 |
| **TiDB** | ① 探测：`tidb_version()`；② DDL 为**异步在线 DDL**（后台 job）——执行耗时统计与超时语义与普通 MySQL 不同，MVP 记录为已知限制并在文档说明，实施阶段补大 DDL 场景实测（见 [08 §5 风险](08-testing-roadmap.md)）；③ 锁：家族默认锁表方案（`GET_LOCK` 在早期版本为 no-op，可信度：中） |
| **OceanBase-MySQL** | ① `ob_compatibility_mode` 探测分派；② 不假设支持全部 MySQL 8 语法（切分器保守配置）；③ 锁：家族默认锁表方案（`GET_LOCK` 仅 V4.3.1+ 文档化支持，可信度：中） |
| **OceanBase-Oracle** | ① 模式探测分派；② `createLock()` 覆写为 `DBMS_LOCK.ALLOCATE_UNIQUE/REQUEST/RELEASE`（可信度：高，OceanBase 官方 Oracle 兼容文档）；③ **标注"实验性"**：社区版无法创建 Oracle 租户，公共 CI 无法自动化验证（见 [08 §3](08-testing-roadmap.md)） |
| **达梦 DM8** | ① **大小写敏感模式探测**：DM 建库参数 `CASE_SENSITIVE`（建库后不可改）决定标识符处理——连接建立后探测（如 `SF_GET_CASE_SENSITIVE_FLAG()`，实施时确认函数名），据此决定历史表/锁表 DDL 是否加引号及查询系统目录的匹配方式，这是**最易被忽略的关键差异点**；② `compatibleMode=oracle` 伪装防御（§1.1）；③ 锁：家族默认锁表方案（无官方确认的 DBMS_LOCK，可信度：中）；④ PL/SQL 块语法与 Oracle 高度兼容，切分器直接用家族配置 |

## 5. 历史表/锁表的幂等创建

`SchemaHistoryDdl` 返回两组 DDL（历史表 + 锁表，见 [04 §2.2](04-parser-lock-tx.md)），由 `SchemaHistory.ensureExists()` 调用：

1. PG 系/MySQL 系：直接 `CREATE TABLE IF NOT EXISTS`（幂等）。
2. Oracle 系：先查系统目录判断存在性；不存在则建表，并捕获"表已存在"错误码兜底（两个进程同时初始化的竞态窗口）。
3. 建表动作发生在获取锁**之前**（锁表本身需要先存在），因此建表必须自身幂等且容忍竞态——这是流程顺序上的硬约束（见 [05 §1](05-commands.md) 时序第 4~5 步）。

## 6. 扩展新方言的成本模型（二期验收标准）

新增一个 PG 系国产库（如瀚高）的预期工作量，用于验证本设计的扩展性：

1. 新建 `HighgoDatabase extends PostgreSQLFamilyDatabase`（覆写探测串与差异点，约 100 行）；
2. 新建 `HighgoDatabaseType`（URL 前缀 + 阶段二线索，约 50 行）；
3. `META-INF/services` 注册一行；
4. integration-tests 增加一个 `DatabaseTestSupport` 实现（约 50 行）跑通契约测试套件。

**core 其他代码零改动**。若实施中发现做不到，说明家族基类抽象泄漏，需回到设计层修正。
