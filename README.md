[English](./README.en.md) | 中文

# Flydb

Flydb 是面向任意支持 JDBC 驱动的数据库的 Schema 版本化迁移工具：内置主流数据库方言，以国产信创数据库支持为特色，并通过 `DatabaseType` SPI 扩展小众 JDBC 数据库。

项目采用 Java 8 基线。`flydb-core` 保持零第三方运行时依赖；独立 CLI 从 `drivers/` 动态加载 JDBC 驱动，不把数据库厂商驱动捆绑进发行包。

> Flydb 2.0 正在按 [实施计划](./docs/design/09-implementation-plan.md) 分阶段交付。当前代码已覆盖 core 命令、SQL 解析、历史仓储、锁与事务语义、主流与信创内置方言，以及独立 CLI。数据库兼容状态以实际测试证据为准，不把方言实现等同于生产认证。

## 能做什么

- 版本化迁移 `V1__init.sql`、可重复迁移 `R__view.sql`、撤销迁移 `U1__init.sql`
- `migrate`、`info`、`validate`、`baseline`、`repair`、`clean`、`undo`
- advisory lock 或锁表互斥、DDL 事务差异、失败记录阻断与 repair 恢复
- UTF-8 Properties、环境变量、命令行参数、密码文件和占位符
- migrate/undo 的 `--dry-run`，只完成探测、校验、解析和打印，不执行 SQL
- 外置 JDBC 驱动和方言 SPI，适配不能公开分发驱动的信创数据库

## 数据库状态

| 数据库家族 | 内置方言 | 当前验证层级 |
|---|---:|---|
| MySQL 8 | 是 | 自动化兼容测试；CLI 发行包端到端验证 |
| PostgreSQL | 是 | 自动化兼容测试 |
| 达梦 DM8 | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| 人大金仓 KingbaseES | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| openGauss | 是 | 方言与驱动元数据契约测试；真实环境认证待补 |
| OceanBase / TiDB | 复用对应家族 | 轻量兼容测试；真实环境覆盖持续补充 |
| 其他 JDBC 数据库 | 可扩展 | 需提供 JDBC 驱动及 `DatabaseType` SPI 方言实现 |

## 五分钟上手

前置条件：Java 8 或更高版本、一个已创建的目标数据库，以及与 Java 8 兼容的 JDBC 驱动。

```bash
unzip flydb-cli-2.0.0.zip
cd flydb-cli-2.0.0

# 示例：把 mysql-connector-j.jar 放入 drivers/
cp /path/to/mysql-connector-j.jar drivers/

bin/flydb init \
  --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user \
  --database-type mysql \
  --yes
```

`init` 会生成 `flydb.conf`、`db/migration/V1__init.sql` 和本项目专用的 `drivers/README.md`，并拒绝覆盖已有文件。编辑首个迁移脚本后执行：

```bash
export FLYDB_PASSWORD='replace-me'

bin/flydb --dry-run migrate
bin/flydb migrate
bin/flydb info
bin/flydb validate
```

密码也可通过 `flydb.password=${env:DB_PASSWORD}` 或 `flydb.password.file=/run/secrets/db_password` 提供。不要把明文密码提交到版本库。

## 脚本命名

```text
V1__create_user.sql       # 版本化迁移，只成功应用一次
V1.1__add_status.sql      # 点分版本号
R__refresh_user_view.sql  # checksum 变化后再次执行
U1__create_user.sql       # 撤销最近一次已应用的 V1
```

默认位置为 `filesystem:db/migration`。SQL 支持 `${key}` 占位符；命令行用 `-Dkey=value` 传入。未定义占位符会在执行前报错并指出脚本行号。

## 配置优先级

```text
CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值
```

配置文件查找顺序为 `--config` 指定文件、当前目录 `flydb.conf`、安装目录 `conf/flydb.conf`。未知的 `flydb.*` 配置键会直接报错并给出近似建议。

常用命令：

```bash
bin/flydb migrate
bin/flydb info --color=never
bin/flydb validate
bin/flydb baseline --baseline-version 5
bin/flydb repair
bin/flydb undo

# clean 默认禁用；非交互环境必须同时满足两道开关
bin/flydb clean --clean-disabled=false --force
```

退出码：`0` 成功、`1` 一般错误、`2` 校验失败、`3` 锁冲突或超时、`4` 配置错误、`5` 用户中断。

完整配置、命令语义和错误码见 [配置与 CLI 设计](./docs/design/06-config-cli.md)；架构入口见 [设计总览](./docs/design/00-overview.md)。

## Java API

应用内使用时由调用方管理 `DataSource`，Flydb 不接管连接池生命周期：

```java
Flydb flydb = Flydb.configure()
    .dataSource(dataSource)
    .locations("classpath:db/migration")
    .load();

flydb.migrate();
```

`flydb-core` 不依赖特定连接池、日志框架或 JDBC 驱动。独立 CLI 才负责 URL 配置和 `drivers/` 动态加载。

## 从源码验证

```bash
mvn verify
```

构建产物位于 `flydb-cli/target/flydb-cli-2.0.0-SNAPSHOT.zip`。core 的 JaCoCo 行覆盖率门禁为 80%，并由 Maven Enforcer 保证零非测试运行时依赖。

## 许可证

[MIT](./LICENSE)。JDBC 驱动由使用者自行获取，并遵守各厂商的许可证与分发条款。
