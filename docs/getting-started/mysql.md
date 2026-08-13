# MySQL 8

## 支持边界

MySQL 是 MySQL 家族的基准方言。本仓库已用 MySQL 8 容器完成迁移、失败记录、repair、锁、clean、CLI 和 Spring Boot 2/3 示例验收。MySQL DDL 通常隐式提交，失败后可能留下部分 schema 变化；Flydb 会写入失败记录并要求先 `repair`。

## 驱动与连接

示例使用 Connector/J `8.2.0`。CLI 将 JAR 放入发行包的 `drivers/`；应用则由 Maven/Gradle 自己管理依赖。

```text
jdbc:mysql://127.0.0.1:3306/demo?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
```

```bash
bin/flydb init --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user --database-type mysql --yes
FLYDB_PASSWORD='...' bin/flydb migrate
```

## 权限

迁移账号至少需要创建/修改历史表、锁表和业务对象的 DDL 权限。生产建议将迁移账号与应用账号分离；Spring Boot 用 `flydb.url/user/password` 配置独立账号。

## 已知限制

- `GET_LOCK` 不是 MVP 依赖，默认使用通用锁表。
- 大型在线 DDL 的耗时与锁等待由 MySQL 版本和存储引擎决定，应在目标版本预演。
- `R__...sql` 是可重复迁移；`R1__...sql` 会报 `FLYDB-2005`。
