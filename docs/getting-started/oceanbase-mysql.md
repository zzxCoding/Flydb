# OceanBase-MySQL

## 支持边界

OceanBase-MySQL 复用 MySQL 家族实现，并通过 `ob_compatibility_mode` 做模式探测。当前证据是 MySQL 8 上的探测代理与兼容契约；真实 OceanBase 租户需在显式环境验证。

## 驱动与连接

使用 OceanBase 官方 JDBC 驱动和其 JDBC URL。由于同一驱动可能连接不同兼容模式，建议显式设置：

```bash
bin/flydb init --url 'jdbc:oceanbase://127.0.0.1:2881/demo' \
  --user flydb_user --database-type oceanbase --yes
```

## 已知限制

- 不假设所有 MySQL 8 语法都可用，应在目标租户版本执行 dry-run 和真实预演。
- MVP 默认使用通用锁表，不依赖 `GET_LOCK`。
- 请按 OceanBase 版本和租户模式核对 DDL 权限、连接驱动及在线 DDL 行为。
