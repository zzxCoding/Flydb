# 02 核心领域模型与 API 设计

> [← 01 模块划分](01-modules.md) | [返回总览](00-overview.md) | 下一篇：[03 数据库方言层](03-dialects.md)

所有签名为 Java 8 语法草案，实施时允许微调方法名/参数，但**不可变性、SPI 边界、状态推导规则**是契约，变更需回到设计评审。

## 1. `Flydb` 门面

```java
public final class Flydb {

    private final FlydbConfiguration configuration;

    Flydb(FlydbConfiguration configuration) { this.configuration = configuration; }

    public static FlydbConfiguration.Builder configure() { return new FlydbConfiguration.Builder(); }

    public MigrateResult migrate()        { return new MigrateCommand(configuration).execute(); }
    public MigrationInfoService info()    { return new InfoCommand(configuration).execute(); }
    public void validate()                { new ValidateCommand(configuration).execute(); }
    public void baseline()                { new BaselineCommand(configuration).execute(); }
    public RepairResult repair()          { return new RepairCommand(configuration).execute(); }
    public void clean()                   { new CleanCommand(configuration).execute(); }
    public UndoResult undo()              { return new UndoCommand(configuration).execute(); }
}
```

典型用法（这段代码会出现在 README 首屏，是产品的"第一印象"）：

```java
Flydb flydb = Flydb.configure()
    .url("jdbc:dm://localhost:5236")
    .user("SYSDBA")
    .password(System.getenv("DB_PASSWORD"))
    .locations("classpath:db/migration")
    .load();
flydb.migrate();
```

- 门面自身无可变状态，可安全复用/并发调用（并发安全最终由数据库锁保证，见 [04 §2](04-parser-lock-tx.md)）。
- 命名与 `configure()...load()` 链式心智模型参考 Flyway 的成熟形态，但全部为独立实现，不依赖 Flyway 任何代码。

## 2. `FlydbConfiguration`（不可变 + Builder）

```java
public final class FlydbConfiguration {
    private final DataSource dataSource;
    private final List<String> locations;          // 默认 ["classpath:db/migration"]
    private final Charset encoding;                // 默认 UTF-8
    private final String table;                    // 默认 "flydb_schema_history"
    private final MigrationVersion baselineVersion;// 默认 parse("1")
    private final boolean baselineOnMigrate;       // 默认 false
    private final boolean validateOnMigrate;       // 默认 true
    private final boolean outOfOrder;              // 默认 false
    private final MigrationVersion targetVersion; // 默认 null，精确选择
    private final MigrationVersion startVersion;  // 默认 null，包含边界
    private final MigrationVersion endVersion;    // 默认 null，包含边界
    private final Map<String, String> placeholders;
    private final boolean placeholderReplacement; // 默认 true
    private final String placeholderPrefix;        // 默认 "${"
    private final String placeholderSuffix;        // 默认 "}"
    private final List<Callback> callbacks;
    private final boolean cleanDisabled;           // 默认 true（防呆）
    private final int lockTimeoutSeconds;          // 默认 60
    private final String databaseType;             // 显式指定方言名，null=自动探测
    private final ClassLoader classLoader;

    private FlydbConfiguration(Builder b) {
        // 所有集合防御性拷贝 + Collections.unmodifiable* 包装
    }

    public static final class Builder {
        // 字段与上同名可变；每个 setter 返回 this
        public Builder dataSource(DataSource ds) { ... }
        public Builder url(String url) { ... }        // 与 user/password 组合，load() 时构造 DriverDataSource
        public Builder user(String user) { ... }
        public Builder password(String password) { ... }
        public Builder locations(String... locations) { ... }
        public Builder table(String table) { ... }
        public Builder baselineVersion(String version) { ... }
        public Builder baselineOnMigrate(boolean flag) { ... }
        public Builder validateOnMigrate(boolean flag) { ... }
        public Builder outOfOrder(boolean flag) { ... }
        public Builder targetVersion(String version) { ... }
        public Builder startVersion(String version) { ... }
        public Builder endVersion(String version) { ... }
        public Builder placeholders(Map<String, String> placeholders) { ... }
        public Builder placeholderReplacement(boolean enabled) { ... }
        public Builder callbacks(Callback... callbacks) { ... }
        public Builder cleanDisabled(boolean flag) { ... }
        public Builder lockTimeoutSeconds(int seconds) { ... }
        public Builder databaseType(String typeName) { ... }
        public Builder classLoader(ClassLoader cl) { ... }

        public Flydb load() {
            validate();   // 快速失败：url/dataSource 二选一必填；非法组合给 FLYDB-4xxx 错误
            return new Flydb(new FlydbConfiguration(this));
        }
    }
}
```

配置项完整清单及 CLI/环境变量映射见 [06 §2](06-config-cli.md)。`Builder.validate()` 在 `load()` 时快速失败，错误消息指明具体非法项——不允许"配置了但没生效"的静默行为（旧原型 `max_concurrent_tasks` 之教训）。

## 3. `MigrationVersion`

```java
public final class MigrationVersion implements Comparable<MigrationVersion> {

    private final List<Part> parts;         // 数字 BigInteger / 小写字母 token，不可变
    private final String rawValue;

    public static MigrationVersion parse(String versionText) {
        // 以数字开头；字母数字 token 可由 .、_、- 分隔，否则 FLYDB-2001
        // 数字 token 用 BigInteger，不对范围做隐藏假设；字母 token 不区分大小写比较
    }

    @Override public int compareTo(MigrationVersion other) {
        // 逐段比较，短的一方缺失段按 0 处理 → "1.2" 与 "1.2.0" 相等
    }
    @Override public boolean equals(Object o) { /* 与 compareTo==0 一致 */ }
    @Override public int hashCode() { /* 跳过尾部零段，保证与 equals 一致 */ }
    @Override public String toString() { return rawValue; }
}
```

`V20260327-b06.4__data.sql` 这类版本按 token 自然顺序参与范围、版本族、`out-of-order` 和重复检测。扫描到以版本化/撤销前缀开头且后缀匹配、但整体命名无法解析的候选文件时必须报 `FLYDB-2001`，不得静默忽略。

**设计取舍（明示）**：`1.2` ≡ `1.2.0`（末尾补零不改变语义）；`equals`/`hashCode`/`compareTo` 三者严格一致，避免"放入 HashSet 判重失败"的经典缺陷。字母数字 token 可由点、下划线或连字符分隔；数字优先按 BigInteger 比较，字母不区分大小写比较，保证日期前缀范围和同日前缀版本顺序稳定。

## 4. 迁移元数据

```java
public enum MigrationType { SQL, JDBC, BASELINE, UNDO_SQL }

public interface ResolvedMigration {
    MigrationVersion version();      // 可重复迁移（R__）返回 null
    String description();
    String script();                 // 相对路径（SQL）或类全限定名（JDBC）
    Integer checksum();              // Java 迁移允许 null（不参与校验）
    MigrationType type();
    MigrationExecutor executor();    // 屏蔽 SQL 脚本与 Java 迁移的执行差异
}

public interface AppliedMigration {
    int installedRank();             // 单调递增记账序号（不是版本号）
    MigrationVersion version();      // 可重复迁移为 null
    String description();
    MigrationType type();
    String script();
    Integer checksum();
    String installedBy();
    Timestamp installedOn();         // Java 8 基线，用 java.sql.Timestamp 贴合 JDBC
    int executionTimeMillis();
    boolean success();
}
```

`MigrationExecutor` 是执行抽象（[04 §1.4](04-parser-lock-tx.md)）：SQL 迁移由解析器产出语句列表逐条执行；Java 迁移回调用户实现。

## 5. `MigrationResolver` SPI 与 `JavaMigration`

```java
public interface MigrationResolver {
    Collection<ResolvedMigration> resolveMigrations(ResolverContext context);
}

public interface ResolverContext {
    List<String> locations();
    Charset encoding();
    String sqlMigrationPrefix();          // "V"
    String repeatableMigrationPrefix();   // "R"
    String undoMigrationPrefix();         // "U"
    String sqlMigrationSeparator();       // "__"
    String sqlMigrationSuffix();          // ".sql"
    ClassLoader classLoader();
    // 可选高级发现规则通过 Java 8 default 方法提供：directory/file/path glob/regex、
    // versionSource、migrationOrder、directoryVersionRegex。
}
```

内置两个 Resolver：

1. **SqlMigrationResolver**：递归扫描 `locations`（支持普通目录、JAR 内 `classpath:` 与 `filesystem:`），先按规范化相对路径执行 glob/regex 发现过滤，再按命名规范解析版本/描述/类型并计算 checksum；`script` 保留相对 location 的路径。
   - **确定性排序**：默认按文件 `MigrationVersion`；显式 `directory-version` 时按提取的目录版本、文件版本、相对路径排序。目录版本模式要求文件版本属于目录版本族，避免排序规则与历史版本语义分裂。
   - **版本来源**：筛选坐标可来自文件版本或目录版本；目录版本只用于发现/筛选/排序，历史表仍记录文件的完整版本和相对脚本路径。
   - **重复版本检测**：两个文件解析出相同版本号 → `FLYDB-2002` 报错（列出冲突文件路径）。
   - **旧式命名阻断**：发现 `R\d+__*.sql` → `FLYDB-2005` 报错并给出重命名指引（见 [00 §4.1](00-overview.md)），不提供关闭开关。
2. **JavaMigrationResolver**：从配置的包路径扫描/显式注册 `JavaMigration` 实现。

```java
public interface JavaMigration {
    MigrationVersion version();      // null => 可重复迁移
    String description();
    Integer checksum();              // 允许 null；建议对逻辑关键参数自算
    void migrate(Context context) throws Exception;

    interface Context {
        Connection connection();     // 已定位到目标 schema，事务边界由框架管理
        FlydbConfiguration configuration();
    }
}
```

第三方可通过 `META-INF/services/com.flydb.core.resolver.MigrationResolver` 注册额外 Resolver（例如从远端配置中心拉脚本），core 汇总去重后统一排序。

## 6. `MigrationInfo` 与状态推导真值表

```java
public enum MigrationState {
    PENDING,        // 本地有、库里无、版本高于已应用最高版本
    OUT_OF_ORDER,   // 本地有、库里无、版本低于已应用最高版本（outOfOrder=false 时 migrate 报错）
    SUCCESS,
    FAILED,         // 库里有 success=false 记录 → 阻塞 migrate，需 repair
    MISSING,        // 库里有、本地文件已不存在
    OUTDATED,       // 可重复迁移：本地 checksum 已变化，等待重跑
    FUTURE,         // 库里记录的版本高于本地所有脚本版本（代码回滚了、库没回）
    BASELINE,       // baseline 合成记录
    UNDONE          // 该版本最新记录为 UNDO，当前视为未应用
}

public final class MigrationInfo implements Comparable<MigrationInfo> {
    private final ResolvedMigration resolved;   // 可为 null（MISSING/FUTURE）
    private final AppliedMigration applied;     // 可为 null（PENDING/OUT_OF_ORDER）
    private final MigrationState state;
    // 静态工厂 derive(...) 是纯函数，info/validate/migrate 三个命令共用同一实现
}
```

**状态推导真值表**（单测的核心真值表，逐行覆盖，见 [08 §1](08-testing-roadmap.md)）：

| resolved | applied(success=true) | applied(success=false) | 其他条件 | 状态 |
|---|---|---|---|---|
| 有 | 无 | 无 | version > 已应用最高版本 | `PENDING` |
| 有 | 无 | 无 | version < 已应用最高版本 | `OUT_OF_ORDER` |
| 有 | 有 | — | checksum 相同 | `SUCCESS` |
| 有（版本化） | 有 | — | checksum 不同 | validate 失败（`FLYDB-2003`） |
| 有（可重复） | 有 | — | checksum 不同 | `OUTDATED`（待重跑） |
| 有 | — | 有 | | `FAILED` |
| 无 | 有 | — | 非 BASELINE/UNDO | `MISSING` |
| 无 | 有 | — | version > 本地最高版本 | `FUTURE` |
| — | 有（type=BASELINE） | — | | `BASELINE` |
| 有/无 | 该版本最新记录 type=UNDO_SQL | — | | `UNDONE`（若本地 V 文件仍在 → 重新参与 pending 计算） |

"当前已应用最高版本"的计算**只统计 `success=true` 且未被 UNDO 的版本化记录**——修复旧原型缺陷 #3。

## 7. Checksum 规则

- 算法：**CRC32**（`java.util.zip.CRC32`，跨 JVM/跨平台稳定）——替代旧原型的 `String.hashCode()`。
- 输入：脚本原始文本按 **UTF-8**（或配置的 encoding）读取，**行尾归一化**（`\r\n` → `\n`）后的字节流。
- 时机：**占位符替换之前**计算。理由：checksum 反映"版本受控的脚本文件本身"；若替换后计算，不同环境占位符取值不同会导致校验和漂移，`validateOnMigrate` 在生产误报"脚本被篡改"。
- 存储：`flydb_schema_history.checksum`（INT，CRC32 低 32 位按有符号整数存储）。
- `ChecksumCalculator` 为独立纯函数类，单测直接覆盖 CRLF/编码/BOM 边界（BOM：读取时剥离 UTF-8 BOM 再计算）。

## 8. 结果对象

```java
public final class MigrateResult {
    private final List<AppliedMigrationSummary> executed;  // 本次执行的迁移
    private final MigrationVersion targetVersionReached;   // 达到的版本，可能为 null
    private final long totalExecutionTimeMillis;
    private final List<String> warnings;                   // 如锁降级提示
}

public final class RepairResult {
    private final List<String> removedFailedRecords;       // 被清除的失败记录（脚本名）
    private final List<String> alignedChecksums;           // 被对齐 checksum 的脚本名
}

public final class UndoResult {
    private final MigrationVersion undoneVersion;
    private final long executionTimeMillis;
}
```

全部不可变；`MigrationInfoService` 提供 `all()` / `pending()` / `applied()` / `current()` 只读视图，供 CLI 的 `info` 表格与 starter 日志复用。

## 9. 异常与错误码

```java
public class FlydbException extends RuntimeException {
    private final ErrorCode errorCode;    // 稳定枚举，如 FLYDB_1001
    // getMessage() 输出格式：[FLYDB-1001] 中文简述（英文简述）
    //                        可能原因: ...
    //                        建议操作: ...
}

public final class FlydbValidationException extends FlydbException {
    private final List<ValidationProblem> problems;  // validate 收集全部问题一次性抛出
}
```

错误码分段：`1xxx` 连接/探测、`2xxx` 迁移与校验、`3xxx` 并发锁、`4xxx` 配置。完整清单与文案规范见 [06 §5](06-config-cli.md)。
