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

OceanBase 4.2.1.2（Oracle 模式）实测中，`DBMS_LOCK.ALLOCATE_UNIQUE` 可调用，但 `REQUEST`/`RELEASE` 不可用；Flydb 当前不启用该包，统一使用 Oracle 家族的锁表行锁。目录查询与 DDL 权限仍因环境而异，接入前建议先 `--dry-run migrate` 预演。MySQL 兼容租户见 [OceanBase-MySQL](oceanbase-mysql.md)，当前为轻量兼容测试。
