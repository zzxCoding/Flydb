# openGauss

## 支持边界

openGauss 复用 PostgreSQL 家族实现。当前有 PostgreSQL 家族兼容契约和 URL/产品名两阶段探测；真实 openGauss 实例仍需显式环境验证。

## 驱动与连接

优先使用 openGauss 专用 JDBC 驱动和 `jdbc:opengauss://` URL，并显式指定方言：

```bash
bin/flydb init --url 'jdbc:opengauss://127.0.0.1:5432/demo' \
  --user flydb_user --database-type opengauss --yes
```

若使用 PostgreSQL 驱动连接兼容 URL，产品名兜底探测存在残余风险，因此生产配置仍建议使用专用驱动和 URL。

## 权限与限制

迁移账号需要 schema、历史表和锁相关对象的 DDL 权限。请在目标版本上验证认证方式、扩展、函数和 advisory lock 行为。
