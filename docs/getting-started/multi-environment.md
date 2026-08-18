# 多数据库多环境自动化

面向需要用 Flydb CLI 同时管理多个数据库家族、多套测试与生产环境的开发和运维人员。Flydb 0.2 没有内置的环境 profile 机制，本文给出一套完全基于现有 CLI 契约的组织模式：**一个数据库×环境一份 `flydb.conf`，密码全部外部注入，所有环境执行同一套命令序列**。配置键、命令参数和错误码以[配置项参考](../reference/configuration.md)、[CLI 命令参考](../reference/commands.md)和[错误码参考](../reference/errors.md)为准，本文不重复维护。

## 1. 总体模式

多环境自动化由三块现有能力拼出：

| 能力 | 作用 |
|---|---|
| `-c, --config <file>` | 为每个数据库×环境绑定一份独立配置 |
| `FLYDB_*` 环境变量 | 优先级高于 `flydb.conf`，用于注入密码等环境差异 |
| 稳定退出码 | CI 按校验失败、锁冲突、配置错误等类别分流处理 |

配置优先级统一为 `CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值`。

## 2. 配置组织：一个数据库×环境一份 flydb.conf

按 `<数据库>.<环境>` 命名，集中放在迁移仓库的 `deploy/` 目录并纳入版本控制：

```text
deploy/
├── flydb.mysql.uat.conf
├── flydb.mysql.prod.conf
├── flydb.dm.uat.conf
└── flydb.dm.prod.conf
```

示例（只放非敏感项，密码一律不进文件）：

```properties
flydb.url=jdbc:mysql://db-uat.example.com:3306/app
flydb.user=flydb_ddl
flydb.database-type=mysql
flydb.locations=filesystem:/opt/deploy/migrations/mysql
```

要点：

- **自动化中永远显式传 `-c/--config`。** 不传时 CLI 按"当前目录 `flydb.conf` → 安装目录 `conf/flydb.conf`"隐式查找，而 CI 和堡垒机的工作目录不可控，隐式查找是配置漂移的主要来源；隐式查找只适合本地交互使用。
- **`flydb.locations` 写绝对路径。** `filesystem:` 相对路径以执行 CLI 时的工作目录为基准；`init` 生成的配置同样使用绝对路径。
- **未知 `flydb.*` 键直接报 `FLYDB-4001`**，配置文件因此天然是一份可校验的环境清单：拼错键名会在流水线最早一步失败，而不是静默生效。
- SQL 占位符按环境差异化取值时，在各环境 conf 中写 `flydb.placeholders.<key>`，或由流水线统一用 `-D<key>=<value>` 传入；业务运行时模板要原样入库时设置 `flydb.placeholder-replacement=false`。
- 环境细节不能进版本库时，可退化为"一份 `flydb.conf` + 每环境一组 `FLYDB_URL`、`FLYDB_USER`、`FLYDB_LOCATIONS` 环境变量"；可评审性不如每环境一个文件。

## 3. 密码按环境分层注入

| 环境 | 推荐方式 | 说明 |
|---|---|---|
| 本地临时测试 | `flydb.password` 明文 | 仅限本地，不要提交 |
| 测试 / CI | `FLYDB_PASSWORD` 或 `flydb.password=${env:DB_PASSWORD}` | 由 CI secret 系统注入 |
| 生产 / 共享 | `flydb.password.file=/run/secrets/db_password` | 由 Vault、KMS 或部署系统落盘，收紧文件权限 |

- 自动化中不要使用 `-p/--password`：命令行参数会进入进程列表、shell history 和 CI 日志。
- 每个环境使用独立的专用 DDL 账号，迁移账号与业务账号分离；Spring Boot 应用按[配置项参考](../reference/configuration.md#spring-boot)用 `flydb.url/user/password` 做同样的隔离。

## 4. 脚本仓库：按数据库家族分目录

```text
migrations/
├── mysql/    V1__init.sql   V2__add_order.sql
├── dm/       V1__init.sql   V2__init_data.sql
└── oracle/   V1__init.sql
```

每个 conf 的 `flydb.locations` 指向自己家族的顶层目录。各数据库的版本流互相独立，历史表位于各自的目标库，天然隔离，这是推荐做法。

- 同一数据库内多个应用共用 schema 时，用 `flydb.table` 区分历史表。
- `directory-glob`/`path-glob` 等路径过滤也能在同一 locations 下切分子集，但过滤会影响 `info`、`validate`、`repair`、`undo` 看到的全部本地集合；能用分 locations 解决就不要用过滤。过滤规则见[配置项参考](../reference/configuration.md#版本选择路径过滤与排序)。
- 调整脚本目录期间，把新旧 locations 逗号并列过渡，避免已应用记录变 `MISSING` 报 `FLYDB-2003`，详见[迁移位置与已有历史](../reference/configuration.md#迁移位置与已有历史)。

## 5. 流水线：所有环境同一套命令序列

```bash
CONF=deploy/flydb.dm.prod.conf

bin/flydb -c "$CONF" version             # 各环境工具版本一致性检查
bin/flydb -c "$CONF" validate            # checksum、失败记录、迁移集合
bin/flydb -c "$CONF" --dry-run migrate   # 输出待执行清单，供人工或门禁核对
# ── 生产环境在此设置审批门：核对 dry-run 清单与目标库摘要，获得明确授权后 ──
bin/flydb -c "$CONF" migrate
bin/flydb -c "$CONF" info --color=never  # 状态留档
bin/flydb -c "$CONF" validate            # 迁移后复核
```

- **环境晋升就是换一个 `-c`。** 测试与生产使用同一份脚本产物、同一个发行包 ZIP，只替换 conf 路径。各环境统一锁定同一个 Flydb 版本，流水线开头的 `version` 即检查点；发行包自带版本匹配的 `docs/` 与 Skill。
- **机器消费加 `--json`**：stdout 单行 JSON 信封、stderr 仅诊断，CI 用 `jq` 提取字段而不是解析文本表格；GitHub Actions 与 Jenkins 的完整示例见 [CI 集成指南](ci-integration.md)。
- **退出码做门禁**：按[错误码参考](../reference/errors.md#cli-退出码)分类处理——`2` 校验失败直接阻断，`3` 锁冲突可配置自动重试与告警（`flydb.lock-timeout-seconds` 按最长迁移时长设置），`4` 配置错误回退到配置阶段修复。
- **迁移只能有一个执行者。** 要么 CI 统一执行 CLI，要么应用启动时由 Spring Boot starter 执行；两边都跑虽然会被迁移锁串行，但结果依赖时序。常见分工是测试环境用 starter 省事、生产走 CI 加审批，生产应用可用 `flydb.enabled=false` 关闭自动迁移。
- 远程库大批量数据迁移用 `--batch-size` 提速，MySQL 家族同时在 URL 上加 `rewriteBatchedStatements=true`，见[配置项参考](../reference/configuration.md)。
- 可选：对生产定时执行只读 `validate`，checksum 不一致通常意味着有人绕过工具手工修改脚本，可作为漂移告警。

## 6. 存量库先 baseline 再自动化

已有历史的生产库接入前，先人工对账已应用到的版本，写入基线后再交给流水线：

```bash
bin/flydb -c "$CONF" baseline --baseline-version 20260801
```

非空库首次接入也可以在 conf 中设置 `flydb.baseline-on-migrate=true`；两种方式都应先在测试环境演练。迁移失败留下的失败记录会阻断后续 `migrate`（`FLYDB-2004`）；不要在流水线里自动 `repair`——它修改历史表，必须先由人确认修复策略。

## 7. 驱动分发与离线执行机

- CI runner 与生产执行机的镜像中预置安装目录 `drivers/`，或把厂商驱动发布到企业私服，conf 写 `flydb.driver-coordinate` 并用 `--maven-settings` 指向私服 settings；解析顺序与私服语义见[JDBC 驱动自动解析](../reference/configuration.md#jdbc-驱动自动解析)。
- 网络受限的生产执行机设置 `flydb.offline=true`，禁止一切远程驱动解析。
- 达梦、KingbaseES、openGauss 的驱动需写完整坐标；驱动解析不处理 Maven 传递依赖，需要伴随 JAR 的厂商驱动应把所有 JAR 一并放入 `drivers/`。CI 可缓存 `~/.flydb/drivers` 减少下载。
- 遵守厂商许可，不重新分发驱动 JAR。

## 8. 当前能力边界

- **MCP 适配尚未交付**（规划于路线图阶段三）：Agent 与 IDE 目前通过 CLI + `--json` 稳定消费结果（见[JSON 输出参考](../reference/json-output.md)），尚无 MCP 服务器形态，见[路线图](../../ROADMAP.md)。
- **没有配置继承或模板**：多份 conf 之间的重复内容，可在流水线中用模板生成后作为制品管理，而不是等待工具内置 profile。
- **`undo` 只回退最近一次版本化迁移，`clean` 是破坏性操作**（默认禁用，非交互需双开关）：两者都不应出现在自动化脚本中，仅在本地排障时人工执行。
- **信创数据库的验证层级以[数据库上手指南](README.md)为准**：达梦、KingbaseES、openGauss 目前为方言与驱动元数据契约测试，接入生产前应先在授权实例完成[JDBC 数据库快速接入](jdbc-integration.md#5-接入后的最小验证清单)中的最小验证清单，不要把单测通过当作厂商兼容证明。

相关页面：[CI 集成指南](ci-integration.md)、[数据库上手指南索引](README.md)、[JDBC 数据库快速接入](jdbc-integration.md)、[配置项参考](../reference/configuration.md)、[CLI 命令参考](../reference/commands.md)、[错误码参考](../reference/errors.md)。
