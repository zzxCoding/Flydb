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
| `--batch-size <n>` | SQL 语句 JDBC 批大小，默认 `1` 逐条执行；远程库大批量 INSERT 建议 `>1`，MySQL 可同时在 URL 加 `rewriteBatchedStatements=true` |
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

执行 `migrate` 时会逐脚本输出进度（序号 `i/N` 与单脚本耗时），长迁移期间可据此判断是否仍在推进；`clean`、`baseline` 不解析本地迁移集合，迁移目录中的非法文件名不会阻断它们。`info` 表格列宽按内容自适应，宽版本号不会错位。

不配置 `--version-selection` 时保持兼容行为：`--target-version` 精确匹配，起止版本按包含边界的版本顺序匹配。`family` 将目标版本作为 token 前缀版本族；`family-range` 包含结束版本族的所有子版本；`regex` 对版本文本做整串匹配。`--version-source=directory` 会把相同目录版本下的多个文件版本作为一个选择集合，例如精确目标 `20230531` 可选择 `V20230531.1`、`.2`、`.3`。注意 range 的结束版本不含其族子版本（`20260625` 不含 `20260625.3`），命中时 `migrate` 与 `--dry-run migrate` 会输出警告提示改用 `family-range`。显式版本选择不执行 `R__...sql`，且不会绕过 checksum、失败记录或 `out-of-order`。

路径 glob/regex 是发现过滤器，会影响 `migrate`、`info`、`validate`、`repair` 和 `undo` 看到的本地迁移集合；同一维度的 glob 与 regex 不可并用，不同维度同时配置时取交集。匹配对象始终是 location 下以 `/` 分隔的相对路径，不是机器绝对路径。

脚本中的 `${...}` 是业务运行时模板、必须原样入库时，使用 `--placeholder-replacement=false`；该开关同时作用于真实迁移和 dry-run。

`clean` 默认禁用。非交互环境必须同时设置 `--clean-disabled=false` 和 `--force`：

```bash
bin/flydb clean --clean-disabled=false --force
```

执行 `clean` 时会先报告 schema 和待删除对象统计，再输出表、视图、序列及 Flydb 记账表的逐项进度，便于判断长时间清理是否仍在推进。Oracle 家族（Oracle/达梦/OceanBase-Oracle）会跳过随表生存的 identity 序列（`ISEQ$$_`），删除表时带 `PURGE` 并在收尾清空回收站，避免残留对象占用名称。

`init` 只会创建不存在的 `flydb.conf`、`db/migration/V1__init.sql` 和缺失的驱动说明；生成配置中的 `flydb.locations` 使用绝对路径，跨目录执行不会随 CWD 漂移。它不会覆盖已有配置或迁移文件，冲突时返回 `FLYDB-4004`。所有子命令均支持 `--help`，例如 `bin/flydb init --help`。

退出码和错误码见[错误码参考](errors.md)，配置键见[配置项参考](configuration.md)。
