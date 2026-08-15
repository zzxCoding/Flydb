# 06 配置体系与 CLI 产品设计

> [← 05 命令语义](05-commands.md) | [返回总览](00-overview.md) | 下一篇：[07 Spring Boot Starter](07-spring-boot-starter.md)

易用性是本项目的明确产品诉求。本篇定义配置体系与 CLI 的完整用户体验。

## 1. 配置优先级与格式

```
CLI 参数  >  环境变量 FLYDB_*  >  配置文件 flydb.conf  >  内置默认值
```

- **格式：Properties**（JDK 内置解析，UTF-8 读取）。不用 YAML——flydb-core 承诺零第三方运行时依赖，引入 SnakeYAML 会打破承诺。若确有 YAML 需求，二期以 `flydb-config-yaml` 可选模块提供、由 CLI 捆绑，不进 core。
- 环境变量映射：`flydb.url` → `FLYDB_URL`；`flydb.placeholders.foo` → `FLYDB_PLACEHOLDERS_FOO`（`.`→`_`，全大写），机械规则无特例。
- 配置文件查找顺序：`--config` 显式指定 > 当前目录 `flydb.conf` > CLI 安装目录 `conf/flydb.conf`。
- **无效键报错**：配置文件中出现无法识别的 `flydb.*` 键 → `FLYDB-4001` 报错（列出未知键与近似建议）。杜绝旧原型"配置了但没有任何代码使用"的静默失效。

## 2. MVP 配置项清单

| 配置键 | 环境变量 | CLI 选项 | 默认值 | 说明 |
|---|---|---|---|---|
| `flydb.url` | `FLYDB_URL` | `-u, --url` | 无（必填） | JDBC URL |
| `flydb.user` | `FLYDB_USER` | `--user` | 无 | |
| `flydb.password` | `FLYDB_PASSWORD` | `-p, --password` | 无 | 支持 `${env:VAR}` 间接引用（§3） |
| `flydb.driver` | `FLYDB_DRIVER` | `--driver` | 按 URL 前缀推断 | 显式驱动类名 |
| `flydb.database-type` | `FLYDB_DATABASE_TYPE` | `--database-type` | 自动探测 | 方言名（[03 §1](03-dialects.md) 逃生舱） |
| `flydb.locations` | `FLYDB_LOCATIONS` | `-l, --locations` | `filesystem:db/migration`（CLI）/ `classpath:db/migration`(API) | 逗号分隔多值；递归扫描子目录；`filesystem:` 支持相对或绝对路径，相对路径以 CLI 当前工作目录为基准 |
| `flydb.encoding` | `FLYDB_ENCODING` | `--encoding` | `UTF-8` | 脚本编码 |
| `flydb.table` | `FLYDB_TABLE` | `--table` | `flydb_schema_history` | 历史表名 |
| `flydb.baseline-version` | `FLYDB_BASELINE_VERSION` | `--baseline-version` | `1` | |
| `flydb.baseline-on-migrate` | `FLYDB_BASELINE_ON_MIGRATE` | `--baseline-on-migrate` | `false` | |
| `flydb.validate-on-migrate` | `FLYDB_VALIDATE_ON_MIGRATE` | `--validate-on-migrate` | `true` | |
| `flydb.out-of-order` | `FLYDB_OUT_OF_ORDER` | `--out-of-order` | `false` | |
| `flydb.target-version` | `FLYDB_TARGET_VERSION` | `--target-version` | 无 | 精确执行版本；与范围互斥 |
| `flydb.start-version` | `FLYDB_START_VERSION` | `--start-version` | 无 | 包含边界的起始版本 |
| `flydb.end-version` | `FLYDB_END_VERSION` | `--end-version` | 无 | 包含边界；不含结束版本的族子版本（`.N`），需包含时用 `family-range` |
| `flydb.version-selection` | `FLYDB_VERSION_SELECTION` | `--version-selection` | 自动推断 | `exact\|range\|family\|family-range\|regex` |
| `flydb.version-source` | `FLYDB_VERSION_SOURCE` | `--version-source` | `file` | `file\|directory` |
| `flydb.version-regex` | `FLYDB_VERSION_REGEX` | `--version-regex` | 无 | 版本整串正则 |
| `flydb.directory/file/path-glob` | 对应 `FLYDB_*_GLOB` | 对应 `--*-glob` | 无 | 相对路径发现过滤 |
| `flydb.directory/file/path-regex` | 对应 `FLYDB_*_REGEX` | 对应 `--*-regex` | 无 | 相对路径整串正则过滤 |
| `flydb.migration-order` | `FLYDB_MIGRATION_ORDER` | `--migration-order` | `version` | `version\|directory-version` |
| `flydb.directory-version-regex` | `FLYDB_DIRECTORY_VERSION_REGEX` | `--directory-version-regex` | 最近的数字目录 | `version` 命名组或第一个捕获组 |
| `flydb.placeholders.<k>` | `FLYDB_PLACEHOLDERS_<K>` | `-D<k>=<v>` | 空 | 用户占位符 |
| `flydb.placeholder-replacement` | `FLYDB_PLACEHOLDER_REPLACEMENT` | `--placeholder-replacement` | `true` | `false` 时 `${...}` 业务模板原样保留 |
| `flydb.placeholder-prefix` | `FLYDB_PLACEHOLDER_PREFIX` | `--placeholder-prefix` | `${` | |
| `flydb.placeholder-suffix` | `FLYDB_PLACEHOLDER_SUFFIX` | `--placeholder-suffix` | `}` | |
| `flydb.sql-migration-prefix` | `FLYDB_SQL_MIGRATION_PREFIX` | `--sql-migration-prefix` | `V` | 版本化迁移前缀 |
| `flydb.repeatable-migration-prefix` | `FLYDB_REPEATABLE_MIGRATION_PREFIX` | `--repeatable-migration-prefix` | `R` | 可重复迁移前缀 |
| `flydb.undo-migration-prefix` | `FLYDB_UNDO_MIGRATION_PREFIX` | `--undo-migration-prefix` | `U` | 撤销迁移前缀 |
| `flydb.sql-migration-separator` | `FLYDB_SQL_MIGRATION_SEPARATOR` | `--sql-migration-separator` | `__` | 版本与描述分隔符 |
| `flydb.sql-migration-suffix` | `FLYDB_SQL_MIGRATION_SUFFIX` | `--sql-migration-suffix` | `.sql` | 脚本文件后缀 |
| `flydb.callbacks` | `FLYDB_CALLBACKS` | `--callbacks` | 空 | Java 回调类名，逗号分隔 |
| `flydb.clean-disabled` | `FLYDB_CLEAN_DISABLED` | `--clean-disabled` | `true` | 防呆 |
| `flydb.lock-timeout-seconds` | `FLYDB_LOCK_TIMEOUT_SECONDS` | `--lock-timeout-seconds` | `60` | |
| `flydb.batch-size` | `FLYDB_BATCH_SIZE` | `--batch-size` | `1` | SQL 语句 JDBC 批大小；`1` 逐条执行并精确定位失败语句，`>1` 减少远程往返但失败序号按批内计数推算 |

## 3. 敏感信息处理

1. **配置文件明文**：`flydb.password=明文密码`——功能上支持，适合本地临时测试；不要提交到版本库或用于共享/生产环境。
2. **环境变量间接引用**：`flydb.password=${env:DB_PASSWORD}`——配置装载时解析，密码本身不落盘、不进日志。
3. **密码文件**：`flydb.password.file=/run/secrets/db_password`（K8s/容器 Secret 挂载场景）。
4. **交互输入**：CLI 检测到密码缺失且连接 TTY → `System.console().readPassword()` 遮罩读取；非 TTY 则报 `FLYDB-4002` 提示可用来源。
5. **统一脱敏**：日志、异常消息、`--dry-run` 输出中，密码一律 `****`；URL 中的内嵌凭据（`user:pass@host`）同样脱敏。

## 4. CLI 命令设计

```
flydb [全局选项] <命令> [命令选项]

命令:
  migrate    执行待应用的迁移            info       查看迁移状态总表
  validate   校验本地脚本与历史记录一致性  baseline   为存量库设置基准版本
  repair     清除失败记录/对齐校验和      clean      清空目标 schema（默认禁用）
  undo       撤销最近一次版本化迁移       init       初始化脚手架
  version    输出 flydb 自身版本
```

全局选项：`-c/--config <file>`、`-u/--url`、`--user`、`-p/--password`、`-l/--locations`、版本选择/来源、路径 glob/regex、迁移排序、`-X/--debug`（完整堆栈）、`-q/--quiet`、`--color=auto|always|never`、`-n/--dry-run`。完整名称以[命令参考](../reference/commands.md)为准。

- **`--dry-run`**（migrate/undo）：完整执行探测/校验/解析/pending 计算，对每条将执行的语句**只打印不执行**——上线评审的刚需。
- **版本选择**（migrate）：默认目标为精确文件版本；显式支持版本族、版本族范围、正则与目录版本来源。显式选择排除 repeatable，且继续服从校验和 `out-of-order`。
- **路径发现过滤**：directory/file/path 三个维度取交集，同一维度 glob/regex 互斥；规则影响所有读取本地迁移的命令。
- **排序**：只提供与版本历史语义一致的 `version` 与 `directory-version`，后者强制校验目录/文件版本族关系。
- **占位符总开关**：`placeholder-replacement=false` 时真实迁移与 dry-run 都跳过替换，允许把 `${...}` 业务运行时模板原样写入数据库。
- **`clean` 双保险**：`cleanDisabled=false` 之外，交互式终端还需输入目标库名确认；非交互需 `--force`。
- **Ctrl+C**：注册 shutdown hook 释放锁连接，退出码 5。

### 4.1 `flydb init` 脚手架

交互模式（TTY）依次询问 URL/用户名/驱动类名/数据库类型，生成：

```
./flydb.conf                          # 含中文注释与全部常用项示例
./db/migration/V1__init.sql           # 示例迁移
./drivers/README.md                   # 各数据库驱动获取指引（Maven 坐标 + 官网下载页）
```

非交互：`flydb init --url jdbc:dm://localhost:5236 --driver dm.jdbc.driver.DmDriver --database-type dm --yes`。
其中 `--driver` 和 `--database-type` 会写入生成的 `flydb.conf`；标准 URL 的驱动类可留空自动推断，厂商 URL 应显式提供。
生成的 `flydb.locations` 使用 `db/migration` 的绝对路径；手工配置相对 `filesystem:` 路径时仍以 CLI 当前工作目录为基准。

### 4.2 `info` 表格输出（中文友好）

```
flydb 0.2.1 · 达梦 DM8 · jdbc:dm://10.0.0.1:5236 · 历史表: flydb_schema_history

版本      描述                     类型   已安装时间            耗时(ms)   状态
-------  ----------------------  -----  -------------------  --------  --------
1         init                    SQL    2026-08-10 09:12:03   128       成功
2         add_status_column       SQL    2026-08-11 10:03:41   45        成功
2.1       fix_status_default      SQL    -                     -         待执行
(可重复)  user_summary_view       SQL    2026-08-11 10:03:42   12        待更新
```

- 状态中文：`待执行/成功/失败/缺失/乱序/未来版本/待更新/基准/已撤销`。
- TTY 着色（绿=成功、红=失败、黄=待执行、灰=缺失）；非 TTY（管道/CI）自动降级纯文本；中文对齐按显示宽度（全角=2）计算，列宽按列内最宽内容自适应（含保底宽度），宽版本号（如 `20260327-b06.4`）不会挤歪后续列。

## 5. 错误码与消息设计

格式（所有 FlydbException 统一）：

```
[FLYDB-3001] 获取迁移锁超时（Lock acquisition timed out）
可能原因: 另一个 flydb 进程正在对该数据库执行迁移；或前次迁移进程异常终止后数据库尚未释放连接。
建议操作: 使用 flydb info 查看锁持有者信息（locked_by/locked_at）；确认无并发迁移后重试。
```

| 区段 | 含义 | 代表 |
|---|---|---|
| `FLYDB-1xxx` | 连接与探测 | 1001 连接失败；1002 无法识别数据库类型；1003 驱动未找到（提示 drivers/ 用法） |
| `FLYDB-2xxx` | 迁移与校验 | 2001 非法版本号；2002 重复版本；2003 checksum 不匹配；2004 存在失败记录需 repair；2005 旧式 R 前缀命名；2006 乱序迁移；2007 baseline 前置不满足；2008 缺少 undo 脚本；2009 未定义占位符 |
| `FLYDB-3xxx` | 并发锁 | 3001 获取锁超时 |
| `FLYDB-4xxx` | 配置 | 4001 未知配置键；4002 缺少必填项；4003 clean 被禁用；4004 init 目标文件已存在；4005 迁移位置不存在 |

退出码约定（写入文档，CI 按码分支：锁冲突可重试，校验失败需人工）：

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 1 | 一般错误（连接失败、SQL 执行失败等） |
| 2 | 校验失败（validate / validateOnMigrate） |
| 3 | 并发锁冲突/超时 |
| 4 | 配置错误 |
| 5 | 用户中断（SIGINT） |

## 6. JDBC 驱动解析与动态加载

- CLI fat jar **不内置任何 JDBC 驱动**：① 许可证隔离（MySQL Connector/J 为 GPL+FOSS 例外、OceanBase 客户端为 LGPL，与 flydb 的 Apache-2.0 分发解耦）；② 信创现实（达梦/金仓驱动经企业内网制品库分发，不能假设可访问公网）。
- `DriverResolver` 依次检查 `<安装目录>/drivers/*.jar`、运行时 classpath、Maven 本地仓库、`~/.flydb/drivers` 缓存；仍未找到时读取 Maven settings 的 mirror、激活 Profile 仓库、server 认证和 proxy，并通过有效镜像解析 Central。`mirrorOf=*` 存在时不得绕过私服。
- 下载的 JAR 写入 Flydb 独立缓存，不修改 Maven 本地仓库；`flydb.offline=true` 或 `flydb.driver-download=never` 可禁止联网。CLI 仍然**不捆绑任何 JDBC 驱动**。
- 解析结果构建子 URLClassLoader；**不走 `DriverManager`**（对非系统类加载器加载的驱动有可见性限制），而是反射实例化 `java.sql.Driver` 后由内置 `DriverDataSource` 直接持有调用 `driver.connect(url, props)`。
- 驱动类名按 URL 前缀内置映射（`jdbc:dm://` → `dm.jdbc.driver.DmDriver` 等），`--driver` 可覆盖；映射表维护在 `DatabaseType` 各实现内。
- 二期外部方言 jar（[01 §2.1](01-modules.md)）同样放 `drivers/` 目录，一并进入 SPI 扫描路径。

## 7. 发行形态

```
flydb-cli-<版本>/
├── bin/flydb  bin/flydb.bat          # 启动脚本（检测 JAVA_HOME，要求 Java 8+）
├── lib/                              # flydb-cli.jar + flydb-core.jar + picocli.jar
├── drivers/README.md                 # 驱动放置说明 + 坐标速查（01 §5）
├── conf/flydb.conf.sample
├── docs/                             # 与发行版本匹配的命令、配置、错误码和数据库指南
├── flydb-skills/                     # 可安装的 flydb-cli Agent Skill 与评测用例
├── AGENTS.md                         # Agent 首次接入、安全边界和 Skill 启用入口
├── LICENSE
├── NOTICE
├── README.en.md
└── README.md                         # 五分钟上手
```

zip 由 `maven-assembly-plugin` 产出，挂到 GitHub Releases；版本匹配的文档和 Skill 随包分发，Agent 在没有源码 checkout 时也能读取真实命令契约。`bin/flydb` 对 JDK 版本给出中文错误提示（低于 8 时）。

## 8. 用户文档规划（仓库 README 重写）

1. **README.md**：定位一句话 → 支持数据库矩阵（真实状态，含"实验性"标注）→ 五分钟上手（CLI 三步 + API 一段代码，即 [02 §1](02-domain-api.md) 示例）→ 指向 docs/。
2. `docs/getting-started/`：每个数据库一页（驱动获取、URL 格式、账号权限要求、已知限制）——信创用户拿到的第一手中文资料，是产品护城河的一部分。
3. `docs/reference/`：配置项全表、错误码全表、命令手册。
4. 从 Flyway 迁移指南：历史表映射说明（`flyway_schema_history` → `flydb_schema_history` 的 baseline 接入路径）。
