# openGauss

## 支持边界

openGauss 复用 PostgreSQL 家族实现。已用 Flydb CLI 0.3.8 在真实 openGauss 5.0.0 实例的 A 兼容模式下完成
连接、方言探测、JDBC 元数据、校验、预演、首次迁移、重复迁移、会话锁互斥与失败 DDL 回滚验证。
现有迁移引擎无需修改；该结论仅覆盖本次版本、模式与驱动，不代表厂商认证。环境、脚本和命令见
[2026-10-22 实测记录](../verification/opengauss-20261022.md)。

## 驱动与连接

优先使用 openGauss 专用 JDBC 驱动和 `jdbc:opengauss://` URL，并显式指定方言：

```bash
cp /path/to/official-release/opengauss-jdbc-5.0.0.jar drivers/
bin/flydb init --url 'jdbc:opengauss://127.0.0.1:5432/demo' \
  --user flydb_user --driver org.opengauss.Driver --database-type opengauss --yes
```

若使用 PostgreSQL 驱动连接兼容 URL，产品名兜底探测存在残余风险，因此生产配置仍建议使用专用驱动和 URL。

openGauss 提供的 `postgresql.jar` 与 PostgreSQL 社区的 `org.postgresql:postgresql` 不是同一驱动制品。
在本地 openGauss 5.0.0 实例上，社区 PostgreSQL JDBC 42.7.12 连接时在认证阶段报
`Invalid SCRAM client initialization`（Flydb `FLYDB-1001`）；相同账号可通过 gsql 登录。
应使用支持 openGauss SHA256 认证的专用驱动，而不是据此改动 Flydb 方言或数据库认证配置。
两种 openGauss JDBC 包的类名、URL 和认证能力见
[官方 5.0.0 JDBC 说明](https://docs.opengauss.org/zh/docs/5.0.0/docs/DeveloperGuide/JDBC%E5%8C%85-%E9%A9%B1%E5%8A%A8%E7%B1%BB%E5%92%8C%E7%8E%AF%E5%A2%83%E7%B1%BB.html)。

本次检查发现 Maven Central 的 `org.opengauss:opengauss-jdbc:5.0.0` 实际包含
`org.postgresql.Driver`，与官方 5.0.0 JDBC 压缩包中的独立 `org.opengauss.Driver` JAR 不同。
不要仅凭文件名推断驱动类；可用 `jar tf <jar>` 核对，并避免把两个同包名驱动放在同一 classpath。
本次实际采用官方发布压缩包内的独立驱动，未提交或加入 Flydb 发行 ZIP。

## 外部实例只读契约

配置 `FLYDB_TEST_OPENGAUSS_URL`、`FLYDB_TEST_OPENGAUSS_USER` 和
`FLYDB_TEST_OPENGAUSS_PASSWORD`，并把已有驱动加入测试 classpath：

```bash
# JDK 17；密码从受控环境注入，不写入命令参数。
./mvnw -B -pl flydb-integration-tests -am \
  -Dtest=OpenGaussDatabaseContractTest#openGaussRealInstanceReadOnlyContract \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.additionalClasspath=/path/to/opengauss-jdbc.jar test
```

该用例只检查连接、方言探测、当前 Schema、JDBC 表元数据和 bigint advisory lock 函数是否存在，
不创建历史表或业务对象，也不获取锁。必须核对实际执行且 `Skipped: 0`；未配置实例时按条件跳过。
只读用例本身不验证迁移、锁竞争或 DDL 回滚；本次另行取得写入授权并完成了这些实测。

锁竞争用例为 `OpenGaussDatabaseContractTest#openGaussRealInstanceLockContract`，另需显式设置
`FLYDB_TEST_OPENGAUSS_LOCK_TESTS=true`。它用独立会话锁检查第二连接超时，以及释放后重新获取；
不创建锁表，不使用业务迁移的锁。该开关只控制测试选择，运行前仍需确认目标与授权范围。

## 权限与限制

迁移账号需要目标 schema 的 DDL/DML 权限及 advisory lock 函数权限。当前实现使用独立连接的会话锁，
不创建锁表。本次目标为 `postgres` 库的 `gaussdb` schema，不能把用户名、库名与 schema 当作同一概念。
其他兼容模式、扩展或部署拓扑仍需单独验证。
