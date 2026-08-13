# TiDB

## 支持边界

TiDB 复用 MySQL 家族 SQL、历史表和锁表实现。当前证据是 MySQL 8 兼容家族契约；这不等同于真实 TiDB 产品认证。公共 CI 的 `tidb` 矩阵项仍使用 MySQL 家族测试，真实 TiDB 需配置外部实例。

## 驱动与连接

通常使用 TiDB 兼容的 MySQL JDBC 驱动和 `jdbc:mysql://` URL，建议显式指定方言以避免产品探测歧义：

```bash
bin/flydb init --url 'jdbc:mysql://tidb.example:4000/demo' \
  --user flydb_user --database-type tidb --yes
```

## 已知限制

TiDB 在线 DDL 可能异步执行，耗时和超时语义不同于普通 MySQL。大 DDL 上线前必须在目标 TiDB 版本做真实演练；MVP 不把 MySQL 兼容测试包装成 TiDB 认证。
