---
name: flydb-cli
description: >-
  使用和排查 Flydb CLI 完成数据库迁移、初始化、校验、状态查看、baseline、repair、clean、undo 和驱动接入。当用户提到 Flydb、bin/flydb、flydb.conf、drivers/、JDBC 驱动、--driver、--database-type，或需要把信创/新型 JDBC 数据库接入 Flydb CLI 时使用。先读取 Flydb 仓库内对应 CLI 文档，再执行最小、可验证的操作；涉及真实数据库写入时保留明确的授权和安全边界。
compatibility: Flydb CLI 0.3，Java 8 或更高版本；需要 Flydb CLI 发行包或源码仓库，以及目标数据库的 JDBC 驱动。
---

# Flydb CLI

使用 Flydb 独立 CLI 完成一次可追溯的数据库迁移操作。这个 Skill 只负责定位文档、选择命令、执行安全检查和汇报结果；命令参数与数据库边界以 Flydb 仓库文档为准。

## 1. 先定位 Flydb 和文档

1. 先确认用户要求使用的是 Flydb CLI，并区分 Flydb 源码仓库、已构建发行包和外部迁移脚本仓库。迁移脚本位于 Flyway 或其他项目中，不代表要改用那个项目的迁移工具；不要只凭目录名猜测执行入口。
2. 定位与目标 CLI 版本匹配的文档。源码仓库应能看到 `flydb-core/`、`flydb-cli/` 和 `docs/`；发行包优先使用包内 `docs/`。先读 [`docs/reference/commands.md`](../../../docs/reference/commands.md)，确认全局选项、命令语义、锁范围和 `--dry-run` 支持范围。
3. 根据任务读取：
   - 配置或环境变量：[`docs/reference/configuration.md`](../../../docs/reference/configuration.md)
   - 错误或退出码：[`docs/reference/errors.md`](../../../docs/reference/errors.md)
   - `--json` 机器输出与 protocolVersion 契约：[`docs/reference/json-output.md`](../../../docs/reference/json-output.md)
   - 多数据库、多测试/生产环境的自动化与 CI 组织：[`docs/getting-started/multi-environment.md`](../../../docs/getting-started/multi-environment.md)
   - 信创/新型 JDBC 数据库、驱动或方言：[`docs/getting-started/jdbc-integration.md`](../../../docs/getting-started/jdbc-integration.md)
   - 某个内置数据库：[`docs/getting-started/README.md`](../../../docs/getting-started/README.md) 及对应页面
   - CLI 发行包、动态驱动和设计约束：[`docs/design/06-config-cli.md`](../../../docs/design/06-config-cli.md)
4. 如果 Skill 被复制到独立目录，以上相对链接可能不可用。此时先从目标 CLI 发行包，再从 Flydb checkout 查找同名文档；两处都没有时报告缺少版本匹配文档，不要自行猜测选项。

## 2. 建立执行上下文

执行任何数据库命令前，明确并在回复中记录：

- 使用的 CLI 路径或发行包目录；
- `flydb.conf` 或 `--config` 来源；
- JDBC URL 的脱敏摘要、目标数据库和方言标识；
- 迁移脚本位置（通常是 `filesystem:db/migration`）；脚本位于外部仓库时记录解析后的绝对位置和当前工作目录；
- 这是本地、测试、预发还是生产数据库；
- 用户要查看、校验、预演还是实际写入。

密码支持直接配置 `flydb.password`（明文，仅建议本地临时测试），也支持 `FLYDB_PASSWORD`、`${env:VAR}` 或 `flydb.password.file`。生产和共享环境优先使用后三者。不要把密码放进命令历史、日志、Skill 输出或 SQL 文件；不要为了确认连接而打印完整 JDBC URL 中的凭据。

优先确认 CLI 能运行：

```bash
bin/flydb version
```

如果没有可用的 `bin/flydb`，先报告缺少发行包/构建产物；不要把源码目录当成已经安装的 CLI，也不要未经请求启动数据库或下载厂商驱动。

## 3. 选择命令

| 用户目标 | 命令 | 默认动作 |
|---|---|---|
| 创建配置和迁移目录 | `init` | 只生成本地文件，不连接数据库 |
| 查看迁移状态 | `info` | 读取数据库和本地脚本，不持有迁移锁 |
| 校验 checksum、失败记录和迁移集合 | `validate` | 只读校验 |
| 预演迁移 | `--dry-run migrate` | 探测、解析并打印 SQL，不执行 SQL |
| 执行待迁移脚本 | `migrate` | 写入数据库并持有迁移锁 |
| 为存量库写入基线 | `baseline` | 写入历史记录并持有迁移锁 |
| 清理失败记录或对齐 checksum | `repair` | 修改历史表并持有迁移锁 |
| 撤销最近一次版本化迁移 | `undo` 或 `--dry-run undo` | `undo` 会执行 SQL 并持有迁移锁 |
| 清空目标 schema | `clean` | 高风险破坏性操作，默认禁用 |

命令细节不要在 Skill 中重新维护；以上表格只帮助选择入口，实际参数以命令参考为准。需要程序化消费结果（CI 脚本、结构化汇报）时加 `--json`：stdout 是单行 JSON 信封，stderr 仍是人类日志；schema 以 JSON 输出参考为准，不要解析中文文本表格。

## 4. 推荐执行流程

### 只读任务

对 `info`、`validate` 或 `version`，执行命令后报告退出码和关键结果。不要把只读命令扩展为 `migrate`、`repair` 或 `baseline`。

### 迁移任务

1. 使用版本选择或路径过滤时，先读配置参考中的“版本选择、路径过滤与排序”，根据有效 locations、过滤条件和选择模式列出预期迁移集合；不要把 locations 下全部 `.sql` 文件数直接当成范围迁移数量。
2. 先运行 `validate`，尽早发现 checksum、失败记录、非法命名或未定义占位符。
3. 再运行 `--dry-run migrate`，确认目标方言、待执行脚本、SQL 数量和脚本顺序，并把预期集合与 dry-run 清单逐项核对。任何未解释的缺失或多出脚本都应阻断实际写入。
4. 本地/测试库可以在用户明确要求后执行 `migrate`；预发/生产库先展示 dry-run 结果、集合核对结果和目标摘要，得到明确的实际写入授权后再执行。
5. 执行后用 `info --color=never` 和 `validate` 核对状态，并报告是否产生失败记录。

如果迁移失败，先读取错误码和数据库原始错误，判断是驱动/连接、方言、脚本还是数据库权限问题。不要自动执行 `repair`；它会修改历史表，必须在用户确认修复策略后执行。

### 新数据库或厂商驱动

1. 读取 [`docs/getting-started/jdbc-integration.md`](../../../docs/getting-started/jdbc-integration.md)，区分 JDBC 驱动与 Flydb 方言。
2. 按接入指南和错误消息中的解析轨迹检查驱动来源。URL 无法自动推断时显式传 `--driver <class>` 和 `--driver-coordinate <groupId:artifactId:version>`；厂商不提供 Maven 制品时再手工放入 `drivers/`。
3. 语法兼容不等于迁移语义兼容。确认 DDL 事务、历史表 DDL、锁、引号/大小写和存储过程切分后，才复用 `mysql` 或 `oracle`；否则使用唯一名称的 `DatabaseType` SPI。
4. 自定义方言 JAR 必须包含 `META-INF/services/com.flydb.core.dialect.DatabaseType` 注册文件，并与目标 `flydb-core` 版本兼容。
5. 首次接入先在授权测试实例执行 `validate`、`--dry-run migrate`，再用无害迁移验证历史表、锁和失败恢复语义。不要把 MySQL 冒烟结果描述为厂商认证。

## 5. 安全边界

- `migrate`、`baseline`、`repair`、`undo` 和 `clean` 都可能改变数据库；目标环境或授权不明确时先停在 dry-run/只读检查。
- `clean` 默认禁用；除非用户明确要求并完成目标确认，不得追加 `--clean-disabled=false --force`。
- 不删除、重命名或改写迁移脚本来“修复”历史状态，除非用户明确要求修改代码/脚本。
- 不下载、提交或重新分发厂商 JDBC 驱动；遵守厂商许可证和企业制品库规则。
- 不把未识别的厂商数据库强行标记为 `mysql`/`oracle` 以绕过探测错误；先读取接入指南并核对语义。
- 不将完整密码、带凭据的 URL 或数据库返回的敏感信息放入最终汇报。

## 6. 常见错误处理

按错误码读取 [`docs/reference/errors.md`](../../../docs/reference/errors.md)：

- `FLYDB-1001`：先检查 URL、账号、密码、网络和数据库状态；不要先改方言。
- `FLYDB-1002`：检查 URL 前缀和方言选择；兼容数据库应显式 `--database-type`，有专有语义则接入 SPI。
- `FLYDB-1003`：按消息中的解析轨迹检查实际 `drivers/`、Maven 本地仓库、settings 私服/镜像认证、驱动坐标、类名和 Java 版本；离线环境确认 `flydb.offline` 与本地制品是否匹配。
- `FLYDB-2001`：按消息定位无法解析的 `V`/`U` SQL 候选或版本；未修正前不得忽略文件继续写入。
- `FLYDB-2003`：按详情区分 checksum 不一致、`MISSING` 和 `FUTURE`。`MISSING`/`FUTURE` 先检查 locations、当前工作目录、路径过滤和代码版本；不要用 repair 掩盖本地迁移集合不完整。
- `FLYDB-2004`：先修正失败脚本并确认历史修复策略，再由用户决定是否 repair；不要直接重跑或自动 repair。
- `FLYDB-2009`：先判断 `${...}` 是 Flydb 迁移占位符还是要原样入库的业务运行时模板；后者使用 `placeholder-replacement=false`，不要随意为模板变量赋值。
- `FLYDB-3001`：确认没有并发迁移，再考虑调整锁等待时间。
- `FLYDB-4001`/`4002`/`4004`/`4005`：按配置参考检查键名、配置来源、必填项或 init 目标文件冲突；`4004` 不要通过删除或覆盖已有文件来绕过，先确认备份和目录；`4005` 核对 locations 前缀、路径与执行时的工作目录。

## 7. 汇报格式

完成后用简洁结构汇报：

1. **目标**：脱敏后的 CLI 路径、数据库/方言、环境和命令。
2. **动作**：实际执行了哪些命令，是否包含 dry-run，是否写入数据库。
3. **结果**：退出码、预期与实际迁移集合核对、迁移数量/状态、失败记录或锁结果。
4. **验证**：`info`、`validate`、MySQL 冒烟或厂商实例契约分别验证了什么。
5. **后续**：只给与当前失败或用户目标直接相关的下一步，不凭空扩展部署或认证范围。
