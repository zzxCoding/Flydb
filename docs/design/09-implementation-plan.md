# 09 实施交接计划（面向执行 Agent）

> [← 08 测试与路线图](08-testing-roadmap.md) | [返回总览](00-overview.md)

**本文档是自包含的实施交接书**：一个没有任何前置会话上下文的执行者（人或 AI agent），仅凭 `docs/design/` 目录即可按阶段开工。开工前请通读 [00 总览](00-overview.md)，并在实施每个阶段前精读对应设计文档章节。

## 1. 任务背景（30 秒版）

本仓库正在从"993 行的 Spring Boot REST 原型"推倒重写为 **Flydb 2.0**：面向信创数据库（达梦/金仓/openGauss 等）的类 Flyway JDBC 迁移工具。产品形态 = flydb-core（纯 Java 8 API 库，零第三方运行时依赖）+ flydb-cli（picocli）+ 双 Spring Boot Starter。设计已评审定稿（docs/design/00~08），**不要偏离设计中标注为"契约"与"决策"的内容**；发现设计与现实冲突时，停下来在 PR/issue 中提出，不要自行改设计。

## 2. 全局约束（每个阶段都适用）

1. **TDD 强制**：每个任务先写失败测试（RED）→ 最小实现（GREEN）→ 重构（IMPROVE）。禁止先写实现补测试。
2. **编码规范**：不可变对象优先（集合防御性拷贝 + unmodifiable 包装；只有 Builder 可变）；文件 <800 行（典型 200~400）；函数 <50 行；嵌套 ≤4 层；无硬编码（常量/配置化）；错误显式处理、绝不静默吞异常；参数化 SQL（历史表读写一律 PreparedStatement 绑定参数，禁止字符串拼接值）。
3. **Java 8 字节码**：flydb-core / flydb-cli / starter-2 用 `<release>8</release>` 编译。不可使用 Java 9+ API 与语法。
4. **覆盖率门禁**：JaCoCo 行覆盖 ≥80%，低于即构建失败。
5. **零依赖门禁**：flydb-core 不得新增任何非 test 依赖（enforcer 规则保障，[01 §1](01-modules.md)）。
6. **每阶段收尾**：跑全量构建 + 代码审查（code-reviewer 类工具/agent）+ 常规 commit（`feat:`/`test:`/`refactor:` 前缀，见仓库 git 规范）。
7. **验收命令**以各阶段"验收"小节为准；命令失败不得进入下一阶段。

## 3. 分阶段任务拆解

### 阶段 1：工程骨架 + 领域模型（依赖：[01](01-modules.md)、[02](02-domain-api.md)）

1. **清理旧代码**：删除 `src/`、`flydb.sh`、`logs/`、`target/`；保留 `LICENSE`、`mvnw*`；`README.md` 暂留（阶段 8 重写）。
2. **搭多模块骨架**：根 POM（`revision` 统一版本 2.0.0-SNAPSHOT、compiler release 8、enforcer 零依赖规则、JaCoCo 门禁）+ 5 个子模块空壳（[01 §1](01-modules.md) 表）。
3. **领域模型（TDD）**，全部在 `flydb-core`：
   - `MigrationVersion`（[02 §3](02-domain-api.md)）→ 测试：`MigrationVersionTest`（[08 §1](08-testing-roadmap.md) 用例清单）
   - `MigrationType` / `MigrationState` / `ResolvedMigration` / `AppliedMigration`（[02 §4/§6](02-domain-api.md)）
   - `MigrationInfo.derive(...)` 状态推导纯函数 → 测试：`MigrationInfoStateTest` 覆盖 [02 §6](02-domain-api.md) 真值表全部行
   - `ChecksumCalculator`（CRC32 + CRLF 归一化 + BOM 剥离，[02 §7](02-domain-api.md)）→ `ChecksumCalculatorTest`
   - `FlydbConfiguration` + Builder（[02 §2](02-domain-api.md)）、`FlydbException`/`ErrorCode`（[02 §9](02-domain-api.md)、错误码清单 [06 §5](06-config-cli.md)）、`Log`/`LogFactory`（[01 §4](01-modules.md)）

**验收**：`./mvnw clean verify` 全绿；JaCoCo 报告 core ≥80%；`ls src` 确认旧代码已不存在。

### 阶段 2：SQL 脚本切分状态机（依赖：[04 §1](04-parser-lock-tx.md)）

1. `SqlStatementBuilderConfig`（不可变配置）+ 三个家族预设常量。
2. `SqlScriptLexer`（字符级状态机，每状态一个私有转移方法）+ `SqlScriptParser` + `SqlStatement`（含起始行号）。
3. `PlsqlBlockDetector` Oracle 系实现。
4. 测试 fixture 组织：`src/test/resources/parser/{postgresql,mysql,oracle}/*.sql`，用例清单见 [08 §1 SqlScriptLexerTest](08-testing-roadmap.md)。**先把全部 fixture 与断言写完再实现状态机**。

**验收**：`./mvnw -pl flydb-core test -Dtest=SqlScriptLexerTest` 全绿，含以下必测场景：`$tag$` 内含引号与分号、`DELIMITER //` 存储过程、`CREATE TRIGGER ... / `、CRLF 文件、`#` 注释（仅 MySQL 配置）、末条语句无分号、行号断言。

### 阶段 3：Resolver + 历史仓储 + migrate/info 最小闭环（依赖：[02 §5](02-domain-api.md)、[05 §1/§2](05-commands.md)、[03 §5](03-dialects.md)）

1. `SqlMigrationResolver`（classpath/filesystem 扫描、命名解析、排序、重复版本检测、**旧式 `R\d+__` 阻断 FLYDB-2005**）+ `JavaMigrationResolver` → `SqlMigrationResolverTest`。
2. `PlaceholderReplacer`（[05 §9](05-commands.md)）→ `PlaceholderReplacerTest`。
3. `SchemaHistory` 仓储（ensureExists 幂等建表、findAll、insert(installed_rank=max+1)、参数化 SQL）。
4. `DatabaseType` SPI + `DatabaseTypeRegistry`（两阶段探测，[03 §1](03-dialects.md)）→ `DatabaseTypeRegistryTest`（**必含达梦 compatibleMode=oracle 伪装场景的 mock 回归**）。
5. 家族基类 + **MySQL、PostgreSQL 两个基准方言**（[03 §3](03-dialects.md)）。
6. `PendingCalculator`（[05 §1.1](05-commands.md)）→ `PendingCalculatorTest`。
7. `MigrateCommand`（暂不含锁，单进程语义）+ `InfoCommand` + `Flydb` 门面接线。
8. integration-tests 模块：`DatabaseTestSupport` 抽象 + MySQL/PG Testcontainers 实现 + `MigrateContractTest`（migrate 两个版本 → info 状态正确 → 重跑 migrate 无新执行）。

**验收**：`./mvnw -pl flydb-integration-tests verify -Pmysql,postgresql`（本机 Docker）契约测试全绿。

### 阶段 4：锁 + 事务失败语义 + 其余命令（依赖：[04 §2/§3](04-parser-lock-tx.md)、[05 §3~§10](05-commands.md)）

1. `MigrationLock` 接口 + `AdvisoryLockMigrationLock`（PG 系）+ `TableRowLockMigrationLock`（通用锁表）。
2. migrate 接入锁与 DDL 事务差异处理（PG 整体回滚自愈 / MySQL 记 `success=false`，[04 §3](04-parser-lock-tx.md) 表）。
3. `ValidateCommand`（收集全部问题一次抛出）、`BaselineCommand`、`RepairCommand`、`CleanCommand`（默认禁用 + CleanStrategy）、`UndoCommand`（仅最近一次）。
4. `Callback`/`Event` + SQL 回调文件发现（[05 §8](05-commands.md)）。
5. 集成测试补：`LockContractTest`（双进程/双线程并发 migrate，[04 §4](04-parser-lock-tx.md) 验收要点 2）、`FailureRecoveryContractTest`（PG 自愈 vs MySQL repair 路径）、`CleanContractTest`。

**验收**：MySQL/PG 上全部契约测试绿；故意注入失败脚本后：PG 历史表无痕可重跑，MySQL 出现 FAILED 记录且 migrate 报 FLYDB-2004、repair 后可重跑。

### 阶段 5：信创方言（依赖：[03 §4](03-dialects.md) 覆写点清单、§5 实测清单）

按顺序（每个方言：Type 探测 + Database 覆写 + TestSupport + 契约测试跑通）：

1. **openGauss**（公共镜像可用，先做）：GenericContainer 封装（`opengauss/opengauss` 镜像、`GS_PASSWORD`）；PG 驱动误连的阶段二兜底探测。
2. **TiDB**：`org.testcontainers:tidb`；`tidb_version()` 探测、priority 高于 MySQL。
3. **OceanBase-MySQL**：`testcontainers-oceanbase`；`ob_compatibility_mode` 分派。
4. **KingbaseES**（需外部实例或私有镜像）：先跑 §5 实测清单第 2/3 项，据实测结果定稿探测串与锁实现（advisory 或降级锁表）。
5. **达梦 DM8**（需外部实例或私有镜像）：`CASE_SENSITIVE` 探测（[03 §4](03-dialects.md) 达梦行）；compatibleMode=oracle 集成回归。
6. **OceanBase-Oracle**：实现 + `DBMS_LOCK` 锁；无自动化环境，标注实验性，测试用例写好但默认 Disabled。

**验收**：openGauss/TiDB/OB-MySQL 契约测试在本机 Docker 全绿；金仓/达梦在 `FLYDB_TEST_<DB>_URL` 指向的实例上全绿（无实例则 Disabled 且原因清晰）。

### 阶段 6：CLI（依赖：[06](06-config-cli.md) 全篇）

1. `ConfigLoader`（四层优先级、`${env:VAR}`、未知键报错）→ `ConfigLoaderTest`。
2. picocli 命令树（9 个命令 + 全局选项）、退出码映射（[06 §5](06-config-cli.md) 表）、`--dry-run`、clean 双保险、Ctrl+C shutdown hook。
3. `drivers/` 动态加载（子 URLClassLoader + 反射实例化 Driver，**不走 DriverManager**，[06 §6](06-config-cli.md)）。
4. `info` 中文表格渲染（全角宽度对齐、TTY 着色降级）、`init` 脚手架（交互 + `--yes`）。
5. assembly 打包 zip（[06 §7](06-config-cli.md) 布局）。

**验收**：在**纯 JDK 8** 环境（如 `docker run eclipse-temurin:8`）解压 zip，对 MySQL 容器完成 `init → migrate → info → validate → repair` 全流程；`echo $?` 各场景退出码符合 [06 §5](06-config-cli.md) 约定。

### 阶段 7：Spring Boot Starter（依赖：[07](07-spring-boot-starter.md) 全篇）

1. starter-2（spring.factories，JDK 8）：`FlydbProperties` + `FlydbAutoConfiguration` + `FlydbMigrationInitializer` + DataSource 依赖后处理器 + SLF4J 日志桥接。
2. `ApplicationContextRunner` 条件装配测试矩阵（[07 §5](07-spring-boot-starter.md)）。
3. starter-3（AutoConfiguration.imports，JDK 17）：同构复制适配。
4. 两个最小示例工程（`examples/boot2-demo`、`examples/boot3-demo`）跑通启动迁移。

**验收**：两个示例工程 `./mvnw spring-boot:run` 启动即完成迁移并正常提供服务；`flydb.enabled=false` 时完全不装配。

### 阶段 8：CI + 文档 + 发布准备（依赖：[08](08-testing-roadmap.md)、[06 §8](06-config-cli.md)）

1. GitHub Actions：单测+覆盖率（所有 PR）→ 稳定级 5 方言矩阵 job → licensed-db 门禁 job（达梦/金仓占位）→ 字节码版本校验步骤（jdeps 断言 ≤52）。
2. README 重写（支持矩阵含成熟度分级、五分钟上手、R 前缀破坏性变更醒目告知）；`docs/getting-started/` 每库一页；错误码/配置项参考页。
3. 发布演练：`mvnw -DskipTests deploy -DaltDeploymentRepository=local::file:./target/staging` 校验产物完整性（jar/sources/javadoc/zip）。

**验收**：[08 §6](08-testing-roadmap.md) MVP Done 六条全部满足。

## 4. 阶段依赖图

```
阶段1 ──► 阶段2 ──► 阶段3 ──► 阶段4 ──► 阶段5 ──► 阶段8
                              └──► 阶段6 ──┤
                              └──► 阶段7 ──┘
（阶段6/7 依赖阶段4 完成即可与阶段5 并行）
```

## 5. 实施期必须实测确认的事实清单（设计中可信度 < 高的项）

| # | 待确认事实 | 何时确认 | 确认方法 | 结果影响 |
|---|---|---|---|---|
| 1 | 达梦/金仓/openGauss 驱动 jar 的 class 文件版本 ≤52（Java 8） | 阶段 3/5 引入驱动时 | `jdeps --list-deps` 或 `javap -verbose` 查 major version | 若 >52：锁定旧版本驱动并在文档标注 |
| 2 | KingbaseES `SELECT pg_advisory_lock(1)` 可用性 | 阶段 5 金仓开工第一天 | 真实实例执行 | 不可用 → `createLock()` 降级锁表（设计已预置） |
| 3 | KingbaseES `getDatabaseProductName()` 实际返回值 | 同上 | 真实实例 + `DatabaseMetaData` | 定稿阶段二探测逻辑 |
| 4 | 达梦 `CASE_SENSITIVE` 探测函数（`SF_GET_CASE_SENSITIVE_FLAG()` 或 `V$OPTION`） | 阶段 5 达梦开工时 | 真实实例执行 | 定稿 quoting 策略实现 |
| 5 | 达梦 compatibleMode=oracle 时产品名返回 "Oracle"（设计依据，需本地复现） | 同上 | 真实实例 + URL 参数 | 固化为集成回归用例 |
| 6 | TiDB Testcontainers 模块单进程模式可用性 | 阶段 5 TiDB 开工时 | 本机拉起 | 不可用 → GenericContainer 自封装 |
| 7 | openGauss `CREATE TABLE IF NOT EXISTS` 与历史表 DDL 兼容性 | 阶段 5 openGauss 开工时 | 容器实测 | 必要时覆写 schemaHistoryDdl |
| 8 | TiDB 大 DDL（加索引）下 migrate 的耗时/超时表现 | 阶段 5 尾声 | 容器 + 万行级表实测 | 写入已知限制文档 |

每项确认后：在本文件对应行补记结果（日期 + 结论），并同步修正 03/04 号文档中的可信度标注。

## 6. 人工验收脚本（阶段 8 发布前）

```bash
# 纯 JDK8 容器内，MySQL 8 目标库（密码为演示用合成值）
docker run -d --name flydb-mysql -e MYSQL_ROOT_PASSWORD=test -p 3306:3306 mysql:8
unzip flydb-cli-2.0.0.zip && cd flydb-cli-2.0.0
# 放入 mysql-connector-j.jar 到 drivers/
bin/flydb init --url "jdbc:mysql://127.0.0.1:3306/demo" --user root --yes
echo "CREATE TABLE t1(id INT PRIMARY KEY);" > db/migration/V1__init.sql
FLYDB_PASSWORD=test bin/flydb migrate      # 退出码 0
FLYDB_PASSWORD=test bin/flydb info         # V1 状态=成功
echo "broken sql;" > db/migration/V2__bad.sql
FLYDB_PASSWORD=test bin/flydb migrate      # 退出码 1，错误含脚本名+行号
FLYDB_PASSWORD=test bin/flydb migrate      # 退出码 1，FLYDB-2004 提示 repair
FLYDB_PASSWORD=test bin/flydb repair && rm db/migration/V2__bad.sql
FLYDB_PASSWORD=test bin/flydb migrate      # 恢复，退出码 0
```

## 7. 交接注意事项

- **不要**为图快在 `flydb-core` 引入 Guava/Commons/SLF4J——enforcer 会拦，设计上也是刻意的（[01 §5](01-modules.md)）。
- **不要**把方言差异写成 Lexer 里的 if/else 家族分支——差异必须表达为 `SqlStatementBuilderConfig` 数据（[04 §1.2](04-parser-lock-tx.md)）。
- **不要**在历史表 SQL 中拼接值——一律 PreparedStatement（旧原型教训）。
- **不要**让阶段二探测（产品名）推翻阶段一（URL 前缀）的结论（[03 §1.1](03-dialects.md)）。
- 遇到设计未覆盖的边界：优先按"防呆、显式报错、不静默"原则处理，并记录到 [08 §5](08-testing-roadmap.md) 开放问题表。
