# CLI 命令参考

CLI 形式为：

```text
flydb [全局选项] <命令> [命令选项]
```

## 全局选项

| 选项 | 说明 |
|---|---|
| `-c, --config <file>` | 显式指定 `flydb.conf` |
| `-u, --url <jdbc-url>` | JDBC URL |
| `--user <name>` | 数据库用户 |
| `-p, --password <value>` | 密码；也可在 `flydb.conf` 写 `flydb.password`，生产推荐环境变量或密码文件 |
| `--driver <class>` | 显式 JDBC Driver 类名 |
| `--driver-coordinate <gav>` | 小众/厂商驱动 Maven 坐标 `groupId:artifactId:version` |
| `--driver-download <auto\|never>` | 是否允许按 Maven 有效仓库下载缺失驱动 |
| `--driver-cache <path>` | 自动下载驱动的 Flydb 缓存目录 |
| `--maven-settings <path>` | Maven settings.xml；用于私服、镜像、认证和代理 |
| `--maven-local-repository <path>` | 显式覆盖 Maven 本地仓库目录 |
| `--offline[=true\|false]` | 禁止联网解析驱动，仍检查本地来源 |
| `--database-type <name>` | 显式方言名 |
| `-l, --locations <locations>` | 迁移位置，逗号分隔；每个位置递归扫描子目录 |
| `--encoding <charset>` | SQL 文件编码 |
| `--table <name>` | 历史表名 |
| `--target-version <version>` | 目标版本；默认精确匹配文件版本 |
| `--start-version <version>` | 执行范围起始版本，包含边界 |
| `--end-version <version>` | 执行范围结束版本，包含边界但不含该版本的 `.N` 子版本；需包含时用 `--version-selection family-range`，命中时 `migrate` 输出警告 |
| `--version-selection <mode>` | `exact\|range\|family\|family-range\|regex`；省略时由目标/范围参数推断 |
| `--version-source <source>` | `file\|directory`，默认 `file` |
| `--version-regex <regex>` | `regex` 模式的版本整串匹配表达式 |
| `--directory-glob/--file-glob/--path-glob <glob>` | 按相对父目录、文件名或完整相对路径过滤 |
| `--directory-regex/--file-regex/--path-regex <regex>` | 对同一三个维度进行整串正则匹配 |
| `--migration-order <order>` | `version\|directory-version`，默认 `version` |
| `--directory-version-regex <regex>` | 从相对父目录提取版本；使用 `version` 命名组或第一个捕获组 |
| `-D<key>=<value>` | SQL 占位符 |
| `--placeholder-replacement[=true\|false]` | 是否替换 SQL 占位符，默认 `true` |
| `--batch-size <n>` | SQL 语句 JDBC 批大小，默认 `1` 逐条执行；远程库大批量 INSERT 建议 `>1`，MySQL 可同时在 URL 加 `rewriteBatchedStatements=true`；批量失败仅在驱动提供可靠标记时定位到具体语句，否则报告批次范围 |
| `-X, --debug` | 输出完整异常栈 |
| `-q, --quiet` | 只输出必要结果和错误 |
| `--color=auto\|always\|never` | 控制终端颜色 |
| `-n, --dry-run` | `migrate`/`undo` 只解析、打印，不执行 SQL |
| `--json` | 机器可读输出：stdout 单行 JSON 信封，stderr 仅诊断；零交互。schema 见 [JSON 输出参考](json-output.md) |

## 命令

| 命令 | 动作 | 持有迁移锁 |
|---|---|---:|
| `migrate` | 校验并执行待迁移脚本 | 是 |
| `info` | 输出本地脚本与历史记录状态 | 否 |
| `validate` | 校验 checksum、失败记录、缺失/未来迁移 | 否 |
| `baseline` | 写入一条 baseline 记录，不执行 SQL | 是 |
| `repair` | 清除失败记录、对齐 checksum | 是 |
| `clean` | 删除目标 schema 的表、视图、序列 | 是 |
| `undo` | 撤销最近一次版本化迁移 | 是 |
| `init` | 生成 `flydb.conf`、迁移目录和驱动说明 | 否 |
| `version` | 输出 Flydb 版本 | 否 |

## 常用流程

```bash
bin/flydb init \
  --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user --database-type mysql --yes

FLYDB_PASSWORD='...' bin/flydb --dry-run migrate
FLYDB_PASSWORD='...' bin/flydb migrate
FLYDB_PASSWORD='...' bin/flydb migrate --target-version 3
FLYDB_PASSWORD='...' bin/flydb migrate --start-version 2 --end-version 5
FLYDB_PASSWORD='...' bin/flydb migrate \
  --version-source directory --target-version 20230531 \
  --migration-order directory-version
FLYDB_PASSWORD='...' bin/flydb migrate \
  --version-selection family --target-version 20230531
FLYDB_PASSWORD='...' bin/flydb info --color=never
FLYDB_PASSWORD='...' bin/flydb validate
```

迁移脚本命名：`V1__init.sql`、`V20260327-b06.4__data.sql`、`R__view.sql`、`U1__init.sql`。版本以数字开头，字母数字 token 可用点、下划线或连字符分隔；无法解析的 `V`/`U` SQL 候选会报 `FLYDB-2001`，不会静默跳过。0.2 起 `R1__...sql` 会报 `FLYDB-2005`；失败记录必须先 `repair`，否则后续 `migrate` 报 `FLYDB-2004`。

执行 `migrate` 时会逐脚本输出序号 `i/N` 与单脚本耗时。长脚本每 10 秒或从脚本开头每连续确认成功 1000 条 JDBC 语句，再输出当前脚本、`confirmed/total`、耗时和平均速率；若已经输出过周期进度，完成时补最终计数。单条语句或一个 batch 尚未返回时，时间心跳仍会输出，但确认数不会增长，因此执行中的长 DDL 不会被误报为已完成。日志走诊断通道（CLI stderr / starter SLF4J），`--json` stdout 仍恰好一行。

迁移失败时，诊断通道会在回滚尝试结束后输出“迁移失败执行快照”：包含失败阶段、事务模式、JDBC 已确认执行数、失败定位可信度和事务结果。这里的 `confirmed` 表示从脚本开头到首个已定位失败项之前、JDBC 连续返回成功的语句前缀；驱动在失败后继续执行并返回的项不计入，且确认执行不等于已提交。非事务路径、回滚失败或提交响应未知时必须核验数据库现状，Flydb 不会据此自动重放。batch 的 `EXECUTE_FAILED` 可精确定位，遇错即停计数只能推算，无可靠标记时只给候选批次范围。

`clean`、`baseline` 不解析本地迁移集合，迁移目录中的非法文件名不会阻断它们。`info` 表格列宽按内容自适应，宽版本号不会错位。

### 长时间迁移与后台运行

交互式终端、SSH 或 Agent 工具会话可能先于迁移超时或断开，从而终止前台的 `migrate` 子进程。已完成 `validate` 和 `--dry-run migrate`、已核对目标并获得写入授权后，如预计耗时可能超过当前会话上限，建议使用 `nohup` 托管进程生命周期，并同时保存日志、PID 和退出码。

以下模板中三个路径都应替换为当前机器上的绝对路径；每次启动会在运行根目录下创建新目录，避免误读上一次的 PID 或退出码。从配置预期的工作目录启动，并优先使用 `flydb.password.file` 传递密码：

```bash
export FLYDB_RUN_ROOT=/absolute/path/to/flydb-runs
export FLYDB_BIN=/absolute/path/to/flydb-cli/bin/flydb
export FLYDB_CONFIG=/absolute/path/to/flydb.conf
mkdir -p "$FLYDB_RUN_ROOT"
FLYDB_RUN_DIR="$(mktemp -d "$FLYDB_RUN_ROOT/run-XXXXXXXX")"
export FLYDB_RUN_DIR

nohup sh -c '
  "$FLYDB_BIN" -c "$FLYDB_CONFIG" --color=never --debug migrate
  flydb_exit=$?
  printf "%s\n" "$flydb_exit" > "$FLYDB_RUN_DIR/exit-code.tmp"
  mv "$FLYDB_RUN_DIR/exit-code.tmp" "$FLYDB_RUN_DIR/exit-code"
  exit "$flydb_exit"
' > "$FLYDB_RUN_DIR/migrate.log" 2>&1 < /dev/null &
printf "%s\n" "$!" > "$FLYDB_RUN_DIR/pid"
```

运行期间用有界查询观察状态，避免另一个长时间前台等待：

```bash
ps -p "$(cat "$FLYDB_RUN_DIR/pid")" -o pid=,etime=,stat=
tail -n 50 "$FLYDB_RUN_DIR/migrate.log"
```

`exit-code` 文件是包装器已经收到 Flydb 退出状态的完成信号。在该文件出现之前，工具调用超时、暂时无新日志或定时检查到点都不代表迁移失败；保留现有 PID 并继续观察，不要启动重复的 `migrate`。只有在进程已结束且 `exit-code` 为 `0` 后，才执行 `info --color=never` 和 `validate`。如 PID 已消失但没有 `exit-code`，将结果视为未知，先保存日志并核对数据库历史与对象状态，不自动重放、`repair` 或 `clean`。

`nohup` 只防止终端挂断中止进程，不提供 JDBC 断线重连、自动重试或主机重启后恢复。CI/CD 任务应保持 Flydb 在前台运行以可靠获取退出码，并将 Job 超时设置为大于最长迁移时间。

MySQL/Oracle 家族虽然不支持 DDL 事务，但一份脚本若解析后的所有语句在忽略前导空白和 SQL 注释后，都以 `INSERT`、`UPDATE`、`DELETE` 或 `MERGE` 开头，Flydb 会把整份脚本与成功历史记录放入同一事务，仅在末尾提交一次。常见的多行表头注释不会使纯 DML 脚本退回逐条提交；注释和 SQL 原文仍原样交给 JDBC。含 DDL、过程块、显式事务控制、`WITH` 或未知语句的脚本保持原有非事务语义；不要依赖 Flydb 对迁移写入做自动重连或自动重放。

不配置 `--version-selection` 时保持兼容行为：`--target-version` 精确匹配，起止版本按包含边界的版本顺序匹配。`family` 将目标版本作为 token 前缀版本族；`family-range` 包含结束版本族的所有子版本；`regex` 对版本文本做整串匹配。`--version-source=directory` 会把相同目录版本下的多个文件版本作为一个选择集合，例如精确目标 `20230531` 可选择 `V20230531.1`、`.2`、`.3`。注意 range 的结束版本不含其族子版本（`20260625` 不含 `20260625.3`），命中时 `migrate` 与 `--dry-run migrate` 会输出警告提示改用 `family-range`。显式版本选择不执行 `R__...sql`，且不会绕过 checksum、失败记录或 `out-of-order`。

路径 glob/regex 是发现过滤器，会影响 `migrate`、`info`、`validate`、`repair` 和 `undo` 看到的本地迁移集合；同一维度的 glob 与 regex 不可并用，不同维度同时配置时取交集。匹配对象始终是 location 下以 `/` 分隔的相对路径，不是机器绝对路径。

脚本中的 `${...}` 是业务运行时模板、必须原样入库时，使用 `--placeholder-replacement=false`；该开关同时作用于真实迁移和 dry-run。

`clean` 默认禁用。非交互环境必须同时设置 `--clean-disabled=false` 和 `--force`：

```bash
bin/flydb clean --clean-disabled=false --force
```

执行 `clean` 时会先报告 schema 和待删除对象统计，再输出表、视图、序列及 Flydb 记账表的逐项进度，便于判断长时间清理是否仍在推进。Oracle 家族（Oracle/达梦/OceanBase-Oracle）按当前 schema 从 `all_sequences` 枚举序列，跳过随表生存的 identity 序列（`ISEQ$$_`），删除表时带 `PURGE` 并在收尾清空回收站，避免残留对象占用名称；OceanBase 删除表遇到直接的 `-4007`，或 vendor code `600` 且 `ORA-00600 arguments` 含 `-4007` 时，会等待 `all_tab_columns` 中的列数稳定后有限重试，并跳过 `_...hidden...` DDL 中间表，其他错误不重试。

`init` 只会创建不存在的 `flydb.conf`、`db/migration/V1__init.sql` 和缺失的驱动说明；生成配置中的 `flydb.locations` 使用绝对路径，跨目录执行不会随 CWD 漂移。它不会覆盖已有配置或迁移文件，冲突时返回 `FLYDB-4004`。所有子命令均支持 `--help`，例如 `bin/flydb init --help`。

退出码和错误码见[错误码参考](errors.md)，配置键见[配置项参考](configuration.md)。
