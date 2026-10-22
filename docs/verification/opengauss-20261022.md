# openGauss 5.0.0 实例验证记录（2026-10-22）

## 结论与边界

Flydb CLI 0.3.8 已在用户提供并授权的 openGauss 5.0.0 真实实例、A 兼容模式下通过：

- 专用 JDBC 连接、URL/版本探测、当前 schema 与 JDBC 表元数据。
- `info`、`validate`、`--dry-run migrate`，以及与预演一致的首次迁移。
- 中文、字符串内分号及单引号、引用列名、定点金额、视图和 dollar-quoted PL/pgSQL 函数。
- 连续两次重复迁移均为零变更，四条成功历史记录保持不变。
- 两连接会话锁互斥：第二连接超时，释放后可重新获取。
- 独立失败迁移的 DDL 回滚：新表未残留，失败历史未残留，正常数据不受影响。

现有 PostgreSQL 家族迁移实现无需修改。本次新增显式环境选择的真库契约测试，并更新驱动说明和验证层级。
该结果仅覆盖下述版本、驱动和模式，不代表厂商认证；其他兼容模式、HA/分布式拓扑、性能、
修改 repeatable 内容后的重跑以及 `clean`、`repair`、`baseline`、`undo` 未实测。

## 实测身份

| 项目 | 实测值 |
|---|---|
| 源码基线 | `ec309f6527fb82c0c91daed86b8262ec06f81b23`，本次变更为其上的测试与文档工作区改动 |
| 容器 | `opengauss`；`opengauss/opengauss:5.0.0`，Linux arm64 |
| 镜像 ID | `sha256:3a7d87fe8b033c73b2491ecdfe6db94f6ba655edd76f1132f040baa9ab51e5d2` |
| 镜像 RepoDigest | `opengauss/opengauss@sha256:f8679ef310000452f7d1ce4c7752cee6fe63575fb6aa05fe8807090042645380` |
| `SELECT version()` | openGauss 5.0.0，2023-04-18 10:27:05，aarch64-unknown-linux-gnu |
| JDBC 目标 | `jdbc:opengauss://127.0.0.1:5432/postgres`，本地测试实例 |
| 当前 schema / 用户 | `gaussdb` / `gaussdb` |
| 数据库兼容模式 | `pg_database.datcompatibility = 'A'` |
| 持久化 | `opengauss_data` → `/var/lib/opengauss` |
| CLI / Java | 已发布 CLI 0.3.8，Java 8；契约测试使用 JDK 17 |
| 原始 CLI ZIP SHA-256 | `0821717871fba0c74eb10e53f6aa419ea5e43ac46d73c51e94f876bca5135ece` |
| 实际驱动 | 官方 5.0.0 JDBC 发布压缩包内的 `opengauss-jdbc-5.0.0.jar`，`org.opengauss.Driver` |
| JDBC 压缩包 SHA-256 | `38c4bccfdb6621baf3ac93f4428b1f98c1f9f9602230ad99a7e76a74dcfaff39`，本地计算值 |
| 实际驱动 SHA-256 | `f3a43deaefe89483a7a2b29cb1e05543996b647c015c9f75857364cb5f5828a4` |

用户明确授权本次下载官方驱动，仅用于本地验证。来源为
[官方 5.0.0 arm JDBC 发布包](https://opengauss.obs.cn-south-1.myhuaweicloud.com/5.0.0/arm/openGauss-5.0.0-JDBC.tar.gz)。
驱动放在本地解压后的 `drivers/`，原始发行 ZIP 未修改，JAR 未提交、未纳入 Flydb 发行物。
密码从容器环境在内存中注入 `FLYDB_PASSWORD`，配置只含环境引用；所有验证沿用原实例认证设置。

## 驱动差异

本机 PostgreSQL 社区 JDBC 42.7.12 在认证阶段报 `Invalid SCRAM client initialization`，
CLI 的 `info`、`validate`、dry-run 均退出 1，错误码 `FLYDB-1001`；此时尚未执行任何迁移。
同一账号通过 gsql 登录正常。改用官方独立 `org.opengauss.Driver` 后三个命令全部退出 0。

另行核对的 Maven Central 制品 `org.opengauss:opengauss-jdbc:5.0.0` 实际含
`org.postgresql.Driver`；官方 JDBC 压缩包同时提供该包名版本与独立 `org.opengauss.Driver` 版本。
本次选择后者，避免与集成测试现有 PostgreSQL 依赖的类名冲突。这个发现只针对本次检查的制品版本，
不能据此推断其他版本的包名。参见[官方驱动类与认证能力说明](https://docs.opengauss.org/zh/docs/5.0.0/docs/DeveloperGuide/JDBC%E5%8C%85-%E9%A9%B1%E5%8A%A8%E7%B1%BB%E5%92%8C%E7%8E%AF%E5%A2%83%E7%B1%BB.html)。

## 获授权的写入范围

写入前通过系统目录确认下列对象名均未占用。用户确认：正常迁移、独立故意失败的回滚验证、重复迁移与锁竞争；
保留测试对象，不执行 `clean`、`repair`、`undo` 或删除。

- 正常表 `gaussdb.flydb_og_e2e_20261022`：两行虚构数据。
- 函数 `gaussdb.flydb_og_fn_20261022()`、视图 `gaussdb.flydb_og_v_20261022`。
- 正常历史表 `gaussdb.flydb_og_hist_20261022`。
- 回滚探针表 `gaussdb.flydb_og_rollback_20261022` 和独立历史表 `gaussdb.flydb_og_rb_hist_20261022`。
- 锁契约使用 `gaussdb.flydb_opengauss_lock_contract` 生成独立会话锁 key；不创建该表或锁表。

配置显式指定 `flydb.database-type=opengauss`、`flydb.driver=org.opengauss.Driver`、
`flydb.driver-download=never`、`flydb.offline=true`、`flydb.clean-disabled=true`，
以及绝对迁移目录与专用历史表。配置文件位于本机 `target/opengauss-validation-20261022/opengauss.conf`。
CLI 位于同目录的 `cli/flydb-cli-0.3.8/bin/flydb`。

## 正常迁移输入

四个脚本、五条语句，预演摘要为
`dafb00c060cf5f2cffff786e6261395a02257684b4f9c7052eb632f9fc13ad8d`。

`V1__create_probe.sql`：

```sql
CREATE TABLE flydb_og_e2e_20261022 (
    id BIGINT PRIMARY KEY,
    "CaseLabel" VARCHAR(80) NOT NULL,
    amount NUMERIC(18,2) NOT NULL
);
```

`V2__insert_probe.sql`：

```sql
INSERT INTO flydb_og_e2e_20261022 (id, "CaseLabel", amount)
VALUES (1, 'openGauss 中文验证', 12.34);
INSERT INTO flydb_og_e2e_20261022 (id, "CaseLabel", amount)
VALUES (2, '分号;与引号''验证', 56.78);
```

`V3__function_probe.sql`：

```sql
CREATE FUNCTION flydb_og_fn_20261022() RETURNS INTEGER AS $$
BEGIN
    RETURN 2;
END;
$$ LANGUAGE plpgsql;
```

`R__probe_view.sql`：

```sql
CREATE OR REPLACE VIEW flydb_og_v_20261022 AS
SELECT id, "CaseLabel", amount FROM flydb_og_e2e_20261022;
```

以下命令使用上述 CLI 与配置，密码由环境注入；均带 `--json --color=never`：

| 命令 | 实际结果 |
|---|---|
| `version` | 0.3.8，退出 0 |
| `info`、`validate` | 识别 openGauss，四个脚本 PENDING；校验成功，退出 0 |
| `--dry-run migrate` | 4 个脚本、5 条语句；函数体内部两个分号未被拆成多条语句，退出 0 |
| 首次 `migrate` | 按 V1、V2、V3、R 顺序执行，当前版本 3，退出 0 |
| 连续两次 `migrate` | 均为 `executed:[]`，当前版本仍为 3，退出 0 |
| 写入后 `info`、`validate` | 四个脚本全部 SUCCESS；校验成功，退出 0 |

独立 gsql 查询核对：表与视图均两行，金额合计 `69.12`，引用列中的中文、分号与单引号均正确；
函数返回 `2`，历史记录共四条且全部成功。

## 独立失败迁移与回滚

独立 locations 为本机测试根目录的 `rollback/`，历史表改为 `flydb_og_rb_hist_20261022`。
先 `validate`、再 `--dry-run migrate`，均退出 0；预演为一个脚本、两条语句，摘要为
`0ce88eb4656a74b08b4f495215c7d4f693aec8357bba6bebeca198e3305e0730`。

```sql
CREATE TABLE flydb_og_rollback_20261022 (id INTEGER PRIMARY KEY);
INSERT INTO flydb_og_rollback_20261022 (no_such_column) VALUES (1);
```

实际 `migrate` 按预期在第 2 条语句、起始行 2 失败，退出 1，错误码 `FLYDB-2010`。
诊断为单脚本事务，JDBC 已确认执行 `1/2`，事务结果“已回滚（JDBC rollback 返回成功）”。
随后独立查询确认回滚探针表不存在、独立历史表记录数为 0，正常测试表仍为两行。
回滚配置的 `info` 将脚本显示为 PENDING，`validate` 退出 0；没有重放失败脚本或执行 repair。

## 自动化真库契约

新增 `OpenGaussDatabaseContractTest`：URL 环境变量控制只读测试，锁竞争测试还需独立的
`FLYDB_TEST_OPENGAUSS_LOCK_TESTS=true` 开关。外部驱动通过
`-Dmaven.test.additionalClasspath=/absolute/path/to/official-release/opengauss-jdbc-5.0.0.jar` 注入。

```bash
# JDK 17；FLYDB_TEST_OPENGAUSS_URL / USER / PASSWORD 已从受控环境注入。
./mvnw -o -B -pl flydb-integration-tests -am \
  -Dtest=OpenGaussDatabaseContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.additionalClasspath=/absolute/path/to/official-release/opengauss-jdbc-5.0.0.jar test
```

真实执行结果：**2 项通过、失败 0、错误 0、跳过 0**。锁用例验证第二连接等待后抛出
`FLYDB-3001`，底层 SQLState 为 `57014`；释放首连接锁后，第二连接获取和释放成功。
未配置实例时的定向编译验证为两项按条件跳过；不能把这种 BUILD SUCCESS 当作真库执行证据。

## 本地证据与遗留对象

`target/opengauss-validation-20261022/` 为忽略目录，保存脱敏配置、原始 JSON、stderr、退出码与驱动身份：

- `driver-verification.json`：官方来源、压缩包与两个 JAR 的本地 SHA-256。
- `official-driver/`：连接、校验及两组预演的 JSON、stderr 和退出码。
- `execution/`：首次迁移、两次零变更迁移、预期失败、回滚后校验及独立 gsql 结果。
- `readonly-and-lock-contract-real.log`：两项真实实例契约通过，跳过 0。

正常表、视图、函数、四行成功历史，以及空的回滚历史表均保留。回滚探针表因事务回滚而不存在。
源码变更未改动生产 Java 实现；本次未重新运行完整 reactor、Java 8 构建矩阵或发布新版本。
