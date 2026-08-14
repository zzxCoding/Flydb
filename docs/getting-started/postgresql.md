# PostgreSQL

## 支持边界

PostgreSQL 是 PostgreSQL 家族基准方言，使用事务型 DDL 和 advisory lock。公共 CI 的 `postgresql` 矩阵项会启动 PostgreSQL 16 容器执行契约；本机默认集成命令只启动 MySQL，如需本地 PostgreSQL 请显式选择它。

## 驱动与连接

使用 PostgreSQL JDBC 驱动（示例版本 `42.7.4`）和以下 URL：

```text
jdbc:postgresql://127.0.0.1:5432/demo
```

```bash
bin/flydb init --url 'jdbc:postgresql://127.0.0.1:5432/demo' \
  --user flydb_user --database-type postgresql --yes
```

## 权限与限制

迁移账号需要目标 schema 的 DDL 权限以及历史表/锁相关对象权限。事务型 DDL 在失败时通常可以回滚；仍应在目标 PostgreSQL 版本上预演扩展、并发锁和权限策略。
