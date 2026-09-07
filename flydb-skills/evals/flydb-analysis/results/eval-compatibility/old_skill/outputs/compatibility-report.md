# 方言兼容风险分析

给定 4 个离线文件已检查完成（analysisStatus: completed）。这只表示限定材料处理完成；现有证据不能确认计划可在 OceanBase 4.2.1.2 Oracle 模式执行，不能据此放行。

应优先核验三处：第 2 行同时修改类型声明和非空约束；第 3 行金额整数位容量收窄；第 7 行字典查询没有明确限定 SALES owner，且没有对结果作断言。未执行 SQL、连接数据库、联网或修改输入。

## 范围、目标与计划身份

- 目标来自 [context.md:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/context.md:2)：OceanBase **4.2.1.2，Oracle 模式**，登录用户 **OPS**，current schema **SALES**；catalog、租户和当前权限未知。不能把登录用户直接当作目标 schema，也不能把 Oracle 兼容模式等同于 Oracle 产品。
- [before.sql](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/before.sql:1) 是用户提供的旧定义，采集时间未知，不代表当前数据库；没有相关对象、应用代码、真实数据或厂商版本材料。
- [plan.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/plan.json:7) 被提供者明确标注为**合成成功信封**，不代表真实 Core 生成或数据库执行。success、exitCode=0、dryRun=true 仅是文件中的字段。
- plan.algorithm：flydb-plan-v1。
- plan.id：0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef。
- direction=migrate，targetVersion=2，migrationCount=1，statementCount=5。计划身份原样透传，未重算或改写；id 本身不能证明数据库目标或执行结果。
- 本次把输入作为指定离线材料集，未读取仓库历史。文件 SHA-256 记录在 JSON sources 中，仅用于材料追溯，不是计划摘要。

## 发现

下列 confirmed 表示文本关系已确认，不表示目标数据库必然失败或生产运行已验证。运行后果按所列条件判断。

### F1 · confirmed

计划第 1 条语句在同一条 MODIFY 中将旧定义的 VARCHAR2(16) NULL 改为 VARCHAR2(32) NOT NULL。

同时修改列类型声明与可空性是目标版本的方言兼容核验点。现有材料不足以断言 OceanBase 4.2.1.2 Oracle 模式接受或拒绝该组合。即使语法受支持，现有 NULL 数据也可能阻止非空约束生效。

条件：before.sql 只是旧定义；执行前真实列定义可能不同。；该组合的版本限制、现有 NULL 和并发写入行为均未获得证据。

证据：[E1 · context.md:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/context.md:2)、[E3 · before.sql:1](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/before.sql:1)、[E4 · migration.sql:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:2)、[E7 · plan.json:24](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/plan.json:24)。

后续核验：核对该精确版本对同一 MODIFY 中类型和 NULL 属性变化的支持；若需拆分，由迁移作者修改后交由 Flydb 重新生成计划。；在另行授权后核验真实列定义、NULL 数量及写入条件。

### F2 · confirmed

计划第 2 条语句将旧定义 NUMBER(10,2) 改为 NUMBER(10,4)；按十进制定点精度解释，整数位容量由 8 位变为 6 位。

小数位增加并非单向扩容；整数范围收窄，现有值可能不符合新类型。实际 ALTER 是否允许、是否要求空列、如何转换和处理边界值仍依赖目标版本。

条件：分析依据是用户提供的旧定义，没有真实数据分布。；目标产品类型更改限制、舍入或转换语义没有版本匹配材料。

证据：[E1 · context.md:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/context.md:2)、[E3 · before.sql:1](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/before.sql:1)、[E5 · migration.sql:3](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:3)。

后续核验：核对该版本 NUMBER 精度与小数位修改限制。；在另行授权后检查真实类型、非空值、绝对值范围及转换后是否越界；不能仅以小数位增大认定兼容。

### F3 · confirmed

匿名块查询未带 owner 的 USER_TAB_COLUMNS，并只过滤 TABLE_NAME = CUSTOMER；登录用户 OPS 与 current schema SALES 不同，所得数量只存入局部变量 n，没有断言或对外输出。

若该版本 USER_* 视图按登录用户或对象所有者限定，该查询可能检查 OPS 范围，不能证明 SALES.CUSTOMER 的列状态。即使查询范围正确，它也未检验目标列、类型或 NULL 属性，不能作为迁移前置条件校验。

条件：USER_TAB_COLUMNS 在 OceanBase 4.2.1.2 Oracle 模式中的可用性、owner 语义和权限范围未知，不能直接套用 Oracle 产品行为。；schema 切换不构成该字典查询已经针对 SALES 的证据。

证据：[E1 · context.md:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/context.md:2)、[E6 · migration.sql:4](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:4)。

后续核验：核对该版本字典视图文档；选择能够显式限定 owner 且当前用户有权限读取的视图，并限定 SALES、CUSTOMER 和目标列。；如需前置条件，补充可观测的明确断言，再重新生成计划；本次没有改写 SQL。

### F4 · confirmed

计划第 3 条将 DECLARE 至 END; 作为一个 statement 保存，并与 migration.sql 第 4 至 8 行逐字一致。

提供的计划没有把块内分号拆成独立语句；但这是合成信封，不能证明真实 Core 分句、JDBC 提交方式或该版本 PL 语法已经验证。

条件：没有真实 Flydb 运行产物、驱动信息或目标数据库执行证据。

证据：[E1 · context.md:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/context.md:2)、[E6 · migration.sql:4](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:4)、[E7 · plan.json:24](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/plan.json:24)。

后续核验：取得实际 Flydb 版本与同一输入产生的真实 dry-run 结果，核对块边界；在另行授权的隔离环境验证实际驱动与目标版本。

### F5 · confirmed

计划第 4 条仅把 CUST_TYPE 的声明长度改为 VARCHAR2(64)，未包含 NOT NULL；若前序语句成功，长度将从 32 增至 64。

本条不命中 F1 的类型与非空属性同句组合，不应因含 MODIFY 或 VARCHAR2 就一并判断为不支持。它仍须按目标版本和执行到此步时的真实定义核验，不能凭静态文本保证可执行。

条件：前序语句成功执行及旧快照与真实对象一致尚未验证。；省略 NULL 属性后的约束保留行为、字符长度语义及相关对象限制缺少目标版本证据。

证据：[E3 · before.sql:1](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/before.sql:1)、[E4 · migration.sql:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:2)、[E8 · migration.sql:9](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:9)。

后续核验：按顺序核对执行到本条时的列定义，以及该版本单纯扩长的限制；不将其机械套入 F1 风险模式。

## 关键排除

- migration.sql 第 1 行的 USER_TAB_COLUMNS 与 MODIFY ... NOT NULL 文本：整行是 SQL 注释，不是执行语句，且未成为计划 statement。
- migration.sql 第 10 行字符串中的 ALTER TABLE X MODIFY Y VARCHAR2(20) NOT NULL：它是 SELECT 的字符串字面量，不是嵌套执行的 DDL；不把 X.Y 记为迁移目标。SELECT 语句本身确实在计划中。

## 覆盖与未知项

- 给定材料（inspected）：按指定目录 rg --files 枚举的 4 个文件全部读取。；4/4 是材料读取数量，不是数据库或应用语义覆盖率。
- 计划信封和脚本定位（inspected）：success、exitCode=0、dryRun=true；1 个 migration、5 个 statement 的声明与实际数量一致；5 条 SQL 与其声明源行逐字一致；匿名块作为一个 statement；纯离线结构和文本一致性校验，不验证计划来自真实 Core，也不验证算法摘要。
- 提供的旧 DDL 与顺序变化（inspected）：SALES.CUSTOMER 的列定义；CUST_TYPE 两步 MODIFY；AMOUNT 精度与小数位变化；旧定义不是实时状态，顺序推导以此前步骤成功为条件。
- 目标版本厂商语义（unavailable）：输入无厂商资料，按用户要求未联网；不能给出确定的支持或拒绝结论。
- 真实数据库与数据（unavailable）：没有连接或 SQL 执行；缺少当前元数据、数据、权限及运行结果。
- 相关对象与应用代码（unavailable）：没有提供相关对象及应用源码，不能证明无依赖。

- 没有 OceanBase 4.2.1.2 Oracle 模式的版本匹配厂商文档或行为证据。 最小补充：补充该版本 ALTER/MODIFY、NUMBER、VARCHAR2、PL 和数据字典官方资料；本次离线限制下未联网。
- 没有目标实例当前元数据、租户信息、字符长度语义或对象权限证据；before.sql 采集时间未知。 最小补充：补充脱敏的产品完整版本、登录用户/current schema、SALES.CUSTOMER 当前定义及权限范围。
- 没有 CUST_TYPE 的 NULL 分布与 AMOUNT 的实际值范围、转换边界。 最小补充：由获授权人员提供必要聚合结果，包括 NULL 数量、金额范围与转换校验结果。
- 未提供视图、索引、约束、触发器及应用引用定义。 最小补充：需要评价真实变更影响时补充相关对象和调用材料；此次没有据此断言不存在依赖。
- plan.json 是合成信封，未获得真实 Core、驱动、数据库执行或 DDL 事务与失败恢复证据。 最小补充：另行取得真实版本生成的 dry-run；获授权后验证目标行为及中途失败后的状态，不能假定整组 DDL 原子回滚。

## 建议下一步

先用版本匹配资料确认 F1/F2/F3，再核对真实元数据和必要的数据聚合结果。如目标限制要求拆分语句或更换字典查询，应由迁移作者修改输入，再由 Flydb 重新生成计划。本报告未替换 SQL，未重算修改后的计划 id；未构造确定的厂商错误码或 SQLState。真实 SQL 验证需另行授权，本次没有执行。

## 分析记录

使用指定旧版 flydb-analysis 0.1.0-draft.2，沿用 flydb-impact-report-v1 JSON 约定。宿主 Codex desktop，精确模型标识 unknown。实际读取与校验见 [execution-log.md](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-compatibility/old_skill/outputs/execution-log.md)，诊断见 [diagnostics.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-compatibility/old_skill/outputs/diagnostics.json)，结构校验结果见 [validation-result.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-compatibility/old_skill/outputs/validation-result.json)。结构检查不认证方言语义。
