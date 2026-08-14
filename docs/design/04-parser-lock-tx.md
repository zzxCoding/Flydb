# 04 SQL 脚本解析器、并发锁与事务策略

> [← 03 方言层](03-dialects.md) | [返回总览](00-overview.md) | 下一篇：[05 命令语义](05-commands.md)

本篇覆盖全项目复杂度最高的三块：语句切分状态机（修复旧原型缺陷 #4）、并发迁移锁（修复缺陷 #5）、DDL 事务能力差异下的失败处理。

## 1. SQL 脚本语句切分器

### 1.1 为什么是字符级状态机而非正则

正则无法正确处理：嵌套引号转义（`''`、`\'`）、任意 tag 的 `$tag$...$tag$` dollar-quoting、跨多行才能判定的 PL/SQL 块边界、`DELIMITER` 指令动态改变终止符。因此设计为**单遍扫描的字符级状态机**。

### 1.2 状态与配置

```java
enum LexerState {
    DEFAULT,
    IN_LINE_COMMENT,          // "--"（MySQL 系另支持 "#"）直到行尾
    IN_BLOCK_COMMENT,         // /* ... */，不支持嵌套；/*+ hint */ 原样保留在语句里
    IN_SINGLE_QUOTED_STRING,  // '...'；'' 转义；MySQL 系另支持 \' 转义
    IN_QUOTED_IDENTIFIER,     // "..."（PG/Oracle 系）或 `...`（MySQL 系）
    IN_DOLLAR_QUOTED_BLOCK,   // $tag$ ... $tag$，仅 PG 系；记录当前 tag
    IN_PLSQL_BLOCK            // 仅 Oracle 系：终止符切换为独占一行的 "/"
}

public final class SqlStatementBuilderConfig {          // 不可变；每家族一个实例
    private final char identifierQuoteChar;             // '"' 或 '`'
    private final boolean dollarQuotingSupported;        // PG 系 true
    private final boolean backslashEscapesSupported;     // MySQL 系 true
    private final boolean hashLineCommentSupported;      // MySQL 系 true（# 注释）
    private final boolean delimiterDirectiveSupported;   // MySQL 系 true
    private final PlsqlBlockDetector plsqlBlockDetector; // Oracle 系非 null
    private final String defaultStatementSeparator;      // ";"
}

public interface PlsqlBlockDetector {
    // 输入：当前语句已累积文本（去注释、大写、trim）
    // 判定是否进入 PL/SQL 块：CREATE [OR REPLACE] [EDITIONABLE|NONEDITIONABLE]
    // PROCEDURE/FUNCTION/PACKAGE[ BODY]/TRIGGER/TYPE，或裸 DECLARE / BEGIN 开头
    // （EDITIONABLE VIEW/SYNONYM 是普通单语句，按分号切分，不进块状态）
    boolean startsPlsqlBlock(String statementSoFarUpperTrimmed);
}
```

**家族差异以配置数据表达，不以代码分支表达**——`SqlScriptLexer` 只有一份实现，读取 config 决定行为。这也是满足"文件 <800 行、函数 <50 行"约束的结构保证：Lexer 每个状态一个私有转移方法。

### 1.3 关键规则

| 规则 | 行为 |
|---|---|
| 语句终止 | `DEFAULT` 状态下遇到当前分隔符（默认 `;`）→ 产出一条 `SqlStatement`（记录起始行号，供错误定位） |
| `DELIMITER xxx` 指令 | 仅 MySQL 系启用；`DEFAULT` 状态且整行匹配 `^\s*DELIMITER\s+(\S+)\s*$`（大小写不敏感）→ 词法伪指令，不产出语句，切换当前分隔符——模拟 mysql 客户端行为，兼容含存储过程的脚本 |
| PL/SQL 块 | 进入 `IN_PLSQL_BLOCK` 后，`;` 只是块内分隔，**唯一终止符是独占一行的 `/`**（允许前后空白） |
| dollar-quoting | `$tag$` 开启（tag 可为空即 `$$`），必须匹配同名 `$tag$` 关闭；块内一切字符原样累积 |
| 空语句 | 纯注释/空白切分出的空语句丢弃，不发给数据库 |
| 文件收尾 | EOF 时若累积缓冲非空白 → 产出最后一条语句（允许最后一条不带 `;`） |

### 1.4 执行侧

```java
public interface MigrationExecutor {
    void execute(Connection connection, ExecutionContext context) throws SQLException;
}
```

SQL 迁移执行器：占位符替换（对原始全文，**在词法解析之前**，见 [05 §9](05-commands.md)）→ `SqlScriptParser.parse()` → 逐条 `Statement.execute(sql)`。失败时异常携带：脚本名、语句序号、**起始行号**、驱动原始错误——错误定位到行是易用性的关键一环。

## 2. 并发迁移锁

```java
public interface MigrationLock extends AutoCloseable {
    void acquire();     // 超时抛 FLYDB-3001（含持锁方信息与建议）
    void release();
}
```

持锁范围：`migrate` / `baseline` / `repair` / `clean` / `undo` 全程；`info` / `validate` 不加锁（理由见 [05 §2/§3](05-commands.md)）。

### 2.1 PG 系：会话级 advisory lock

```sql
SELECT pg_advisory_lock(${lockKey});     -- 获取（阻塞式，配合超时控制）
SELECT pg_advisory_unlock(${lockKey});   -- 释放
```

- `lockKey` = CRC32("flydb:" + 历史表全限定名)，同库不同历史表互不阻塞。
- 由**独立的锁连接**持有（不占用执行迁移的连接/事务）；进程崩溃时连接断开，数据库自动释放——无死锁残留。
- **为什么 PG 系不用锁表方案**：锁表方案要求一个贯穿整个 migrate 的长事务，会长时间钉住 PG 的 `xmin` 水位、阻塞 VACUUM 引发膨胀，这是 PG 系生产环境的真实痛点。advisory lock 是会话级的，不占事务，无此问题。
- openGauss 已确认完整保留该函数族（可信度：高，openGauss 官方文档）；KingbaseES 待实测（可信度：低），不可用则自动降级为 §2.2 方案并在 `MigrateResult.warnings` 提示。

### 2.2 通用兜底：锁表行锁（MySQL 系 / Oracle 系 MVP 默认）

**MVP 决策**：MySQL 系与 Oracle 系统一使用锁表方案，不做 `GET_LOCK`/`DBMS_LOCK` 的版本能力探测（TiDB 早期版本 no-op、OceanBase V4.3.1 前不支持、达梦无确认——探测矩阵的实现与测试成本大于收益）。原生命名锁作为性能优化留给二期。例外：OceanBase-Oracle 直接用已确认的 `DBMS_LOCK`。

锁表（随历史表一起初始化，见 [03 §5](03-dialects.md)）：

```sql
CREATE TABLE flydb_schema_lock (
    lock_id     INT PRIMARY KEY,        -- 固定单行 lock_id=1
    locked_by   VARCHAR(200),           -- "主机名/进程标识"，仅观测用
    locked_at   TIMESTAMP               -- 仅观测用
);
INSERT INTO flydb_schema_lock (lock_id) VALUES (1);   -- 初始化时插入，幂等处理
```

获取/释放：

1. 开启**专用锁事务**（独立连接，`autoCommit=false`）；
2. `SELECT lock_id FROM flydb_schema_lock WHERE lock_id = 1 FOR UPDATE`——数据库行锁即互斥本体；
3. 成功后 `UPDATE ... SET locked_by=?, locked_at=<db当前时间>`（仅观测信息）；
4. 该事务贯穿命令全程，命令结束提交/回滚 → 行锁释放；进程崩溃 → 连接断开自动释放；
5. 超时：`Statement.setQueryTimeout(lockTimeoutSeconds)` + 各方言锁等待参数（实施时按方言校准），超时抛 `FLYDB-3001`，消息包含 `locked_by`/`locked_at` 观测信息与建议（"确认是否有另一 flydb 进程正在迁移；若对方已异常终止，其连接断开后锁自动释放"）。

**注意**：互斥的正确性只依赖数据库行锁，`locked_by/locked_at` 纯观测、不作为持锁判据（不搞应用层心跳判活——那是自造分布式锁的常见错误源）。

### 2.3 锁矩阵汇总

| 方言 | MVP 锁实现 | 依据可信度 |
|---|---|---|
| PostgreSQL / openGauss | `pg_advisory_lock`（独立会话） | 高 |
| KingbaseES | 优先 `pg_advisory_lock`，实测不可用则降级锁表 | 低（待实测） |
| MySQL / TiDB / OceanBase-MySQL | 锁表行锁 | —（通用 SQL，无探测依赖） |
| 达梦 DM8 | 锁表行锁 | — |
| OceanBase-Oracle | `DBMS_LOCK` | 高（官方 Oracle 兼容文档） |

## 3. DDL 事务能力与 migrate 失败处理

`Database.supportsDdlTransactions()` 驱动两种截然不同的失败语义：

| | PG 系（`true`） | MySQL 系 / Oracle 系（`false`） |
|---|---|---|
| 执行边界 | 整份脚本的所有语句 + 历史记录插入包在**同一个事务** | 逐条语句执行即隐式提交；历史记录在**独立自动提交事务**中插入 |
| 某语句失败 | `rollback()`——数据库**完全回到迁移前状态**，历史表无痕 | 前 N-1 条已永久生效，数据库处于"部分应用"第三态 |
| 历史表记录 | 不写入（一并回滚） | 写入一行 `success=false`，忠实记账 |
| 恢复路径 | 天然自愈：修复脚本后直接重跑 `migrate`，该版本仍是 PENDING | `FAILED` 记录阻塞后续 migrate；操作员人工核实/修复库状态 → `flydb repair` 清除失败标记 → 重跑。**生产最佳实践：新增迁移版本补救，而非编辑已应用脚本**（写入用户文档） |

补充规则：

- 每条迁移各自一个事务边界（PG 系），**不把多个迁移合并进一个大事务**——失败时能精确知道停在哪个版本。
- Java 迁移（JDBC 类型）同规则：PG 系包事务，失败回滚；其余记 `success=false`。
- `MigrateResult.warnings` 在非事务性 DDL 方言上执行多语句脚本时不告警（正常场景），但在**脚本内混合 DML+DDL 且失败**时，错误消息明确指出"以下语句已生效且无法回滚"，列出已执行语句序号——可观测性弥补语义缺口。

## 4. 本篇对应的验收要点

1. 切分器：对 [08 §1](08-testing-roadmap.md) 列出的 fixture 全绿（含 `$tag$` 嵌套引号、`DELIMITER` 存储过程、PL/SQL 触发器、CRLF 文件、`#` 注释、行号定位）。
2. 锁：同库两进程并发 `migrate` 的集成测试——一方阻塞、双方结果一致、历史表无重复记录；杀掉持锁进程后另一方能在超时窗口内获锁。
3. 失败处理：PG 系故意失败 → 历史表无记录、可直接重跑；MySQL 系故意失败 → `success=false` 记录存在、migrate 被阻塞、repair 后可重跑。
