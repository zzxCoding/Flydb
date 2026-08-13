# OceanBase-Oracle

## 支持边界

OceanBase-Oracle 属于 Oracle 家族，但当前标记为实验性：公共社区环境通常无法创建 Oracle 兼容租户，不能用 MySQL 或 PostgreSQL 兼容测试替代真实证明。

## 驱动与连接

使用 OceanBase 官方 JDBC 驱动和 `jdbc:oceanbase://` URL，显式指定 `oceanbase`，再由租户的兼容模式分派 Oracle 实现，避免同一 URL 的模式歧义：

```bash
bin/flydb init --url 'jdbc:oceanbase://127.0.0.1:2881/demo' \
  --user flydb_user --database-type oceanbase --yes
```

## 已知限制

Oracle 兼容租户的 `DBMS_LOCK`、PL/SQL、目录查询和 DDL 权限必须在企业环境验证。没有真实租户时，仅可把该页作为接入准备清单，不应把兼容家族单测当作产品认证。
