# 人大金仓 KingbaseES

## 支持边界

KingbaseES 复用 PostgreSQL 家族实现，兼容契约在 PostgreSQL 家族上运行；真实 KingbaseES 需要授权实例和厂商驱动。公共 CI 不拉取专有镜像或驱动。

## 驱动与连接

从金仓制品库获取 JDBC 驱动，使用 `jdbc:kingbase8://` URL，并显式指定 `kingbasees`：

```bash
bin/flydb init --url 'jdbc:kingbase8://127.0.0.1:54321/demo' \
  --user flydb_user --database-type kingbasees --yes
```

真实环境契约由 `FLYDB_TEST_KINGBASE_URL`、`FLYDB_TEST_KINGBASE_USER`、`FLYDB_TEST_KINGBASE_PASSWORD` 提供连接信息；驱动需由自建 Runner 以厂商许可方式提供。

## 已知限制

`pg_advisory_lock`、产品名、系统目录兼容性必须在目标 KingbaseES 版本实测；若 advisory lock 不可用，需按实现提供的锁表降级策略评估。
