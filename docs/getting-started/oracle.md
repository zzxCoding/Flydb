# Oracle

## 支持边界

Oracle 官方方言已内置并注册为 `oracle`，复用 Oracle 家族的 PL/SQL 解析、非事务 DDL、历史表和锁表语义。当前仓库没有捆绑 Oracle JDBC 驱动，也不在本地默认启动 Oracle 容器；真实契约需要带厂商驱动的授权 Runner 或外部实例。

## 驱动与连接

从 Oracle 制品库获取与目标 JDK/数据库版本匹配的 `ojdbc` 驱动，放入 CLI 的 `drivers/` 目录，应用则由 Maven/Gradle 自行管理。常见服务名连接格式如下：

```text
jdbc:oracle:thin:@//127.0.0.1:1521/XEPDB1
```

```bash
bin/flydb init --url 'jdbc:oracle:thin:@//127.0.0.1:1521/XEPDB1' \
  --user flydb_user --database-type oracle --yes
FLYDB_PASSWORD='...' bin/flydb migrate
```

真实契约环境变量为 `FLYDB_TEST_ORACLE_URL`、`FLYDB_TEST_ORACLE_USER`、`FLYDB_TEST_ORACLE_PASSWORD`；自建 Runner 必须以厂商许可方式提供 `ojdbc`。

## 权限与已知限制

迁移账号需要目标 schema 的 DDL 权限，以及创建历史表、锁表和业务对象的权限。Oracle DDL 通常不可回滚，失败后可能留下部分 schema 变化；Flydb 会写入失败记录并要求先 `repair`。PL/SQL 块以独占一行的 `/` 结束，目标实例的 PDB、服务名、大小写标识符和锁等待策略应在上线前预演。
