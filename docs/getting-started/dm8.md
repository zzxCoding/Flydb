# 达梦 DM8

## 支持边界

达梦 DM8 使用 Oracle 家族的 SQL 切分、历史表和锁表语义。已在授权本地 DM8 8.1.3.62 实例、大小写不敏感模式完成 CLI 连接、`validate`、`--dry-run migrate`、首次迁移和重复迁移验证。公共 CI 不捆绑 DM 驱动；该结果不代表厂商认证或其他版本、大小写模式已验证。完整环境、命令与结果见 [2026-10-21 实测记录](../verification/dm8-20261021.md)。

0.3.8 修复了历史表重复初始化缺陷：DM8 返回“对象已存在”错误码 `-2124`，原 CLI 0.3.7 仅识别 Oracle `955`，导致第二次 `migrate` 报 `FLYDB-2010`。首次复验使用本地源码修复包 `0.3.7-local.dm8.20261021`，详细记录保留了该验证包的身份；正式版本获取状态以 [GitHub Releases](https://github.com/zzxCoding/Flydb/releases) 为准。

## 驱动与连接

从达梦安装介质或企业制品库获取 JDBC 驱动，使用 `jdbc:dm://` URL，并显式指定 `dm`：

```bash
bin/flydb init --url 'jdbc:dm://127.0.0.1:5236' \
  --user SYSDBA --database-type dm --yes
```

驱动放在 CLI 的 `drivers/` 目录；应用依赖由调用方按厂商许可管理。本次复用本地已有的 `DmJdbcDriver18` 8.1.3.62，未联网下载或分发驱动。达梦驱动没有内置固定 Maven 坐标，只有本地 Maven 缓存时仍需显式配置完整 `flydb.driver-coordinate`，或将现有 JAR 放入 `drivers/`。

真实只读契约环境变量为 `FLYDB_TEST_DM_URL`、`FLYDB_TEST_DM_USER`、`FLYDB_TEST_DM_PASSWORD`；将驱动加入测试运行时 classpath 后可执行：

```bash
# 密码预先从受控环境注入；JDK 17 构建，不会自动执行迁移。
./mvnw -B -pl flydb-integration-tests -am \
  -Dtest=LicensedDatabaseContractTest#dmRealInstanceContract \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.additionalClasspath=/path/to/DmJdbcDriver18.jar test
```

必须核对该用例实际执行且 `Skipped: 0`；只看到 `BUILD SUCCESS` 不等于已连接真库。需要写库验证时，仍应先完成 `validate`、dry-run 和目标范围授权。

## 已知限制

DM 的 `CASE_SENSITIVE` 会影响标识符和历史表 DDL；`compatibleMode=oracle` 时产品名可能伪装为 Oracle，因此 URL 前缀优先。请在目标建库参数和兼容模式下分别预演。
