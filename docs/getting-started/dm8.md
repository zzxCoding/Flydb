# 达梦 DM8

## 支持边界

达梦 DM8 使用 Oracle 家族的 SQL 切分、历史表和锁表语义。当前已完成方言、元数据和驱动契约设计；真实实例验证需要授权环境和厂商驱动，公共 CI 不捆绑 DM 驱动。

## 驱动与连接

从达梦安装介质或企业制品库获取 JDBC 驱动，使用 `jdbc:dm://` URL，并显式指定 `dm`：

```bash
bin/flydb init --url 'jdbc:dm://127.0.0.1:5236/demo' \
  --user SYSDBA --database-type dm --yes
```

驱动放在 CLI 的 `drivers/` 目录；应用依赖由调用方按厂商许可管理。真实契约环境变量为 `FLYDB_TEST_DM_URL`、`FLYDB_TEST_DM_USER`、`FLYDB_TEST_DM_PASSWORD`。

## 已知限制

DM 的 `CASE_SENSITIVE` 会影响标识符和历史表 DDL；`compatibleMode=oracle` 时产品名可能伪装为 Oracle，因此 URL 前缀优先。请在目标建库参数和兼容模式下分别预演。
