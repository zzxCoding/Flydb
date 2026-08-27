# OceanBase-Oracle

## 支持边界

OceanBase-Oracle 属于 Oracle 家族。此前标记为实验性：公共社区环境通常无法创建 Oracle 兼容租户，不能用 MySQL 或 PostgreSQL 兼容测试替代真实证明。

**验证状态（2026-08-14）**：已在授权真实实例的 Oracle 兼容租户完成端到端验证——validate、`--dry-run migrate`、clean、migrate，复用 Oracle 家族方言（PL/SQL 块切分、非事务 DDL、序列与回收站清理、锁表行锁）。验证证据不等于厂商认证。

## 驱动与连接

使用 OceanBase 官方 JDBC 驱动和 `jdbc:oceanbase://` URL，显式指定 `oceanbase`，再由租户的兼容模式分派 Oracle 实现，避免同一 URL 的模式歧义：

```bash
bin/flydb init --url 'jdbc:oceanbase://127.0.0.1:2881/demo' \
  --user flydb_user --database-type oceanbase --yes
```

## 已知限制

OceanBase 4.2.1.2（Oracle 模式）实测中，`DBMS_LOCK.ALLOCATE_UNIQUE` 可调用，但 `REQUEST`/`RELEASE` 不可用；Flydb 当前不启用该包，统一使用 Oracle 家族的锁表行锁。锁表按迁移连接的当前 schema 解析，专用锁连接使用包含该 schema 的全限定锁表名，因此同一租户的 `SX_TRANS` 与 `SX_PARAMS` 可并行迁移；同一 schema 内的迁移仍互斥。若日志或源码显示锁名为 `flydb:flydb_schema_history`，说明运行的不是当前实现，应先用 `bin/flydb version` 并核对发行包来源。目录查询与 DDL 权限仍因环境而异，接入前建议先 `--dry-run migrate` 预演。MySQL 兼容租户见 [OceanBase-MySQL](oceanbase-mysql.md)，当前为轻量兼容测试。

OceanBase 4.2.x（Oracle 模式）还有以下已实测差异：

- `ALTER TABLE ... MODIFY` 不支持只变更数值类型的 scale；不要把真 Oracle 可执行的精度/小数位调整直接复用到该版本，应拆分数据库家族迁移并在目标版本先做无害验证。
- `ORA-01451` 与 `ORA-00955` 采用 Oracle 兼容错误码，但触发条件和可安全忽略的幂等边界不能按真 Oracle 推断。`ORA-01451` 出现时先核对列的实际 nullability/约束，`ORA-00955` 出现时按对象类型查清同名表、序列等对象；仅凭错误码吞掉异常可能掩盖未完成或定义不一致的迁移。
- `clean` 的表/视图按当前 schema 枚举，序列同样必须按该 schema 查询 `all_sequences`；`user_sequences` 只覆盖登录用户，在当前 schema 与登录用户不同时会漏删序列。
- OceanBase 服务端 DDL 进入异步队列后，即使客户端退出也可能继续执行；驱动可能直接返回 `-4007`，也可能返回 `ORA-00600`（vendor code `600`）并把 `-4007` 放在 `arguments` 中。`clean` 会识别这两种形态，查询 `all_tab_columns`，等待该表列数连续稳定后最多尝试删除三次；枚举时跳过 OB 在线 DDL 暴露的 `_...hidden...` 中间表。每次等待中列数未在 30 秒窗口内稳定、目录查询失败或出现其他错误时立即失败，避免无边界重试或吞掉永久错误。

OceanBase JDBC 驱动可在 batch 中遇错继续并于末尾汇总。Flydb 会优先读取 `BatchUpdateException.updateCounts` 中的 `EXECUTE_FAILED` 标记；若驱动没有提供可识别标记，只报告失败批次范围，不再伪造具体语句序号或行号。需要稳定、精确定位时使用默认 `flydb.batch-size=1`。

0.3.1 起，只有 `INSERT`、`UPDATE`、`DELETE`、`MERGE` 的纯 DML 脚本按单脚本事务执行，数据与 Flydb 历史记录在末尾一次提交；这避免远程 OceanBase 大批量数据迁移逐语句提交，并使 JDBC 连接中断后的最终状态保持为整脚本提交或整脚本回滚。Flydb 不会在迁移内部自动重连重放；连接恢复后重新运行 `migrate`，由历史记录判断该脚本是否仍待执行。含 DDL、PL/SQL、`WITH` 或未知语句的脚本保持非事务路径。

0.3.2 修复带表头注释的数据脚本判定：每条解析结果会先跳过前导 `--`、`/* ... */`（MySQL 家族还包括 `#`）再识别首个可执行 token，因此注释不会让纯 DML 脚本退回逐条自动提交。
