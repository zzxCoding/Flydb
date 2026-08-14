# 05 命令语义与执行流程

> [← 04 解析器/锁/事务](04-parser-lock-tx.md) | [返回总览](00-overview.md) | 下一篇：[06 配置与 CLI](06-config-cli.md)

七个命令共用同一套基础设施（探测 → Database → 锁 → 历史仓储 → Resolver → 状态推导），每个命令是 `command/` 包下的一个类。Resolver 按需执行：`clean`、`baseline` 不消费本地迁移集合，不触发 locations 扫描——迁移目录中的非法文件名（`FLYDB-2001`）不会阻断与迁移集合无关的命令。

## 1. `migrate` 完整时序

```
 1. 取得 Connection（DataSource 或内置 DriverDataSource）
 2. DatabaseTypeRegistry.detect(...)          → 方言（03 §1；databaseType 显式指定则跳过探测）
 3. database = type.createDatabase(...)
 4. SchemaHistory.ensureExists()              → 幂等创建历史表 + 锁表（03 §5；必须在获锁前，锁表要先存在）
 5. lock = database.createLock(cfg); lock.acquire()
    ┌──────────────── try（finally 中 release）────────────────┐
 6. │ applied = SchemaHistory.findAll()                        │
 7. │ resolved = 汇总全部 MigrationResolver 输出并排序（02 §5） │
 8. │ if (validateOnMigrate) 执行 §3 校验（失败→中止，未动 schema）│
 9. │ pending = PendingCalculator.compute(resolved, applied)   │
10. │ callbacks.fire(BEFORE_MIGRATE)                           │
11. │ for each m in pending:  // 严格顺序，任一失败立即停止      │
    │     fire(BEFORE_EACH_MIGRATE)                            │
    │     按 04 §3 的事务边界执行 m                              │
    │     SchemaHistory.insert(installed_rank=max+1, ...)      │
    │     fire(AFTER_EACH_MIGRATE / AFTER_EACH_MIGRATE_ERROR)  │
12. │ fire(AFTER_MIGRATE / AFTER_MIGRATE_ERROR)                │
    └──────────────────────────────────────────────────────────┘
13. lock.release()
14. 返回 MigrateResult（executed / targetVersionReached / totalTime / warnings）
```

步骤 11 通过 core 日志抽象输出逐脚本进度（`正在执行迁移 i/N` 与完成耗时），与 clean 的逐对象进度一致；CLI 默认 stderr 可见，starter 经 SLF4J 接收，避免长迁移期间全程不可观测。

### 1.1 pending 计算规则（`PendingCalculator`，纯函数）

1. **FAILED 阻断**：applied 中存在 `success=false` 记录 → 直接 `FLYDB-2004` 中止，提示先 `repair`（修复旧原型缺陷 #3 的第二道防线；第一道是"当前版本"查询只认 success=true）。
2. **baseline 过滤**：版本 ≤ baseline 版本的 resolved 跳过（标记 IGNORED，info 可见）。
3. **outOfOrder**：`false`（默认）时发现版本低于已应用最高版本的未应用迁移 → `FLYDB-2006` 报错（拒绝静默跳过或乱序执行）；`true` 时按版本序插入执行。
4. **可重复迁移**：checksum 与最近一次应用不同（或从未应用）→ 加入 pending，排在所有版本化迁移之后，按 description 排序。
5. **UNDONE 版本**：某版本最新记录为 UNDO 且本地 V 文件仍在 → 重新视为 pending。
6. **版本选择**：`VersionSelection` 统一承载 `exact`、`range`、`family`、`family-range`、`regex`，版本坐标可来自文件或目录。未指定模式时 `targetVersion` 仍推断为精确文件版本，起止参数推断为普通范围，保持兼容。`range` 按版本顺序比较边界，结束版本的族子版本（如 `20260625.3` 相对 `20260625`）数值上更大而被排除；命中该情况时 `migrate` 与 `--dry-run migrate` 输出警告提示改用 `family-range`。显式选择排除无版本号的可重复迁移，但不绕过 FAILED、baseline、outOfOrder 或 checksum 规则。
7. **排序安全**：默认按文件版本的数字/字母 token 自然顺序。目录排序只有在目录版本可提取且文件版本属于目录版本族时成立，因此现有 baseline、最高版本、outOfOrder 和 undo 仍使用同一文件版本顺序；不提供会让这些语义分裂的任意路径 Comparator。

### 1.2 `baselineOnMigrate`

`true` 且历史表不存在且目标 schema 已有用户表（非空库）→ 自动执行一次 baseline（§4）再继续。用于老系统首次接入。空库则正常从头执行全部迁移。

## 2. `info`（只读，不加锁）

- 返回 `MigrationInfoService`：resolved × applied 做全外连接 → 每行推导状态（[02 §6](02-domain-api.md) 真值表）。
- **不加锁的理由**：监控/CI 高频轮询 info 不应与真正的 migrate 互相阻塞；代价是可能读到迁移进行中的中间态——可接受，权威校验始终发生在 migrate 自身持锁的 validate 里。
- 历史表不存在时不报错，返回全部 PENDING（配合 CLI 首次体验：还没 migrate 过也能看到计划）。

## 3. `validate`（只读，不加锁）

- 校验规则（全部收集后经 `FlydbValidationException` 一次性抛出，不是遇到第一个就停——方便一次修完）：
  1. 版本化迁移 checksum 不匹配 → `FLYDB-2003`；
  2. 已应用记录本地缺失（MISSING）→ `FLYDB-2003`（可配置降级为警告：`ignoreMissingMigrations`，二期）；
  3. FAILED 记录存在 → 报告；
  4. FUTURE 记录存在 → `FLYDB-2003`（本地代码落后于数据库）。
- 不加锁的理由：CI 多流水线并行 validate 同一评审库不应互相阻塞。
- `validateOnMigrate=true`（默认）时 migrate 内部复用同一实现（此时已在锁内）。

## 4. `baseline`（加锁）

- 前置：历史表为空（或不存在，先建）。已有版本记录 → `FLYDB-2007` 报错，不允许覆盖。
- 动作：写入一行 `type=BASELINE, version=baselineVersion, success=true` 合成记录，不执行任何脚本。
- 之后 migrate 只执行版本 > baseline 的迁移。

## 5. `repair`（加锁）

两个子动作，`RepairResult` 分别列出：

1. **清除失败记录**：删除全部 `success=false` 行，解除 migrate 阻塞。
2. **对齐 checksum**：对"已应用且本地文件仍存在但 checksum 不同"的版本化迁移，把历史表 checksum 更新为本地文件当前值。

⚠️ 用户文档必须醒目警告：repair 是**记账修复**工具，不改变数据库 schema 本身。生产环境修复问题的正确方式是新增迁移版本；"改已应用脚本 + repair"只适用于尚未分发到其他环境的开发期脚本。

## 6. `clean`（加锁，默认禁用）

- `cleanDisabled=true`（默认）→ `FLYDB-4003` 直接拒绝。必须显式配置 `cleanDisabled=false` 才可用（CLI 上还需 `--i-know-what-i-am-doing` 式二次确认，见 [06 §4](06-config-cli.md)）。
- **MVP 范围（明确缩减）**：删除当前 schema 中的表（含历史表/锁表）、视图、序列——按外键依赖拓扑排序删除（或按方言使用 CASCADE）。存储过程/触发器/自定义类型的清理列为二期增强。各内置方言全对等 Flyway clean 的工作量不应隐性打包进 MVP。
- `CleanStrategy` 由各家族提供实现（[03 §2](03-dialects.md)）。
- clean 是纯破坏性维护操作，不解析本地迁移集合（Resolver 惰性），迁移目录缺失或含非法文件名都不阻断 clean。
- 通过 core 日志抽象输出 schema、对象总数、逐对象删除进度、历史表/锁表删除和完成状态；CLI 默认可见，starter 通过 SLF4J 接收，避免大 schema 清理期间只有最终一行结果。

## 7. `undo`（加锁，"尽力而为"定位）

- 语义：找到当前已应用的**最高**版本化迁移 V{x}，查找对应 `U{x}__*.sql`；存在则执行，并**追加**一行 `type=UNDO_SQL` 新记录（不删除、不覆盖原 VERSIONED 记录——保留完整审计轨迹）。
- 之后状态推导视 V{x} 为 UNDONE（未应用）；本地 V{x} 文件仍在 → 下次 migrate 重新执行。
- **仅支持撤销最近一次**版本化迁移；不支持连续多级撤销、不支持撤销中间版本——在 DDL 隐式提交类数据库上"任意版本回滚"无法安全实现，宁可不提供也不给误用留口子。
- 无对应 U 脚本 → `FLYDB-2008` 报错并提示编写方法。

## 8. Callback 机制

```java
public interface Callback {
    boolean supports(Event event, Context context);
    void handle(Event event, Context context);

    interface Context {
        Connection connection();
        FlydbConfiguration configuration();
    }
}

public enum Event {
    BEFORE_MIGRATE, BEFORE_EACH_MIGRATE, AFTER_EACH_MIGRATE, AFTER_EACH_MIGRATE_ERROR,
    AFTER_MIGRATE, AFTER_MIGRATE_ERROR,
    BEFORE_VALIDATE, AFTER_VALIDATE,
    BEFORE_CLEAN, AFTER_CLEAN,
    BEFORE_BASELINE, AFTER_BASELINE,
    BEFORE_REPAIR, AFTER_REPAIR,
    BEFORE_UNDO, AFTER_UNDO
}
```

三种注册途径，按序触发：

1. 配置显式注册（`callbacks(...)` / 配置项类名）；
2. ServiceLoader SPI 自动发现；
3. **SQL 回调文件**：locations 下命名为 `beforeMigrate.sql`、`afterEachMigrate.sql` 等（事件名驼峰 + `.sql`）的文件被 `SqlCallbackResolver` 自动包装为 Callback——非 Java 用户也能获得回调能力，复用同一套解析/执行引擎。

回调内抛异常 → 中止当前命令（与迁移失败同路径处理）。

## 9. 占位符替换

- 语法：`${key}`，前后缀可配置；替换发生在 checksum 计算**之后**、词法解析**之前**（[02 §7](02-domain-api.md)）。
- 未定义的占位符 → `FLYDB-2009` 报错（列出脚本名与行号），而不是静默保留原文——静默是配置错误的温床。需要字面量 `${` 时支持转义 `$${`。
- 内置变量（`flydb:` 命名空间，避免与用户占位符冲突）：`${flydb:database}`、`${flydb:schema}`、`${flydb:user}`、`${flydb:table}`、`${flydb:timestamp}`（ISO-8601）。
- 用户文档注明：占位符用于标识符/字面量取值，**不应拼接完整 SQL 片段**（可能破坏语句边界识别）。

## 10. 命令 × 锁 × 事务矩阵（速查）

| 命令 | 加锁 | 写历史表 | 改 schema | 失败语义 |
|---|---|---|---|---|
| migrate | ✅ | ✅ | ✅ | 见 [04 §3](04-parser-lock-tx.md) |
| info | ❌ | ❌ | ❌ | 只读 |
| validate | ❌ | ❌ | ❌ | 收集全部问题一次抛出 |
| baseline | ✅ | ✅（合成行） | ❌ | 前置检查失败即中止 |
| repair | ✅ | ✅（删/改行） | ❌ | 记账操作，事务内完成 |
| clean | ✅ | ✅（表被删） | ✅ | 默认禁用；执行中失败按方言事务能力处理 |
| undo | ✅ | ✅（追加行） | ✅ | 同 migrate 失败语义 |
