# OceanBase-Oracle

## 支持边界

OceanBase-Oracle 属于 Oracle 家族。此前标记为实验性：公共社区环境通常无法创建 Oracle 兼容租户，不能用 MySQL 或 PostgreSQL 兼容测试替代真实证明。

**验证状态（2026-08-14）**：已在授权真实实例的 Oracle 兼容租户完成端到端验证——validate、`--dry-run migrate`、clean、migrate，复用 Oracle 家族方言（PL/SQL 块切分、非事务 DDL、`user_sequences` 与回收站清理、锁表行锁）。验证证据不等于厂商认证。

## 驱动与连接

使用 OceanBase 官方 JDBC 驱动和 `jdbc:oceanbase://` URL，显式指定 `oceanbase`，再由租户的兼容模式分派 Oracle 实现，避免同一 URL 的模式歧义：

```bash
bin/flydb init --url 'jdbc:oceanbase://127.0.0.1:2881/demo' \
  --user flydb_user --database-type oceanbase --yes
```

## 已知限制

OceanBase 4.2.1.2（Oracle 模式）实测中，`DBMS_LOCK.ALLOCATE_UNIQUE` 可调用，但 `REQUEST`/`RELEASE` 不可用；Flydb 当前不启用该包，统一使用 Oracle 家族的锁表行锁。锁表按连接的当前 schema 解析，因此同一租户的 `SX_TRANS` 与 `SX_PARAMS` 可并行迁移；同一 schema 内的迁移仍互斥。若日志或源码显示锁名为 `flydb:flydb_schema_history`，说明运行的不是当前实现，应先用 `bin/flydb version` 并核对发行包来源。目录查询与 DDL 权限仍因环境而异，接入前建议先 `--dry-run migrate` 预演。MySQL 兼容租户见 [OceanBase-MySQL](oceanbase-mysql.md)，当前为轻量兼容测试。

0.3.1 起，只有 `INSERT`、`UPDATE`、`DELETE`、`MERGE` 的纯 DML 脚本按单脚本事务执行，数据与 Flydb 历史记录在末尾一次提交；这避免远程 OceanBase 大批量数据迁移逐语句提交，并使 JDBC 连接中断后的最终状态保持为整脚本提交或整脚本回滚。Flydb 不会在迁移内部自动重连重放；连接恢复后重新运行 `migrate`，由历史记录判断该脚本是否仍待执行。含 DDL、PL/SQL、`WITH` 或未知语句的脚本保持非事务路径。

0.3.2 修复带表头注释的数据脚本判定：每条解析结果会先跳过前导 `--`、`/* ... */`（MySQL 家族还包括 `#`）再识别首个可执行 token，因此注释不会让纯 DML 脚本退回逐条自动提交。
