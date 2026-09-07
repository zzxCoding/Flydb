# 方言兼容风险报告

已完成限定离线范围的分析：计划包含 1 个迁移、5 个语句，命中 3 条 warning。目标为用户说明的 **OceanBase 4.2.1.2 Oracle 模式，登录 OPS，current schema SALES**。这些结论是静态风险提示，不代表 SQL 必然失败或已具备执行条件。

| 规则 | 位置 | 结论与最小补查 |
|---|---|---|
| FDA-OB-001 | [migration.sql:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:2) | CUST_TYPE VARCHAR2(16) NULL → VARCHAR2(32) NOT NULL 同时改长度和可空性。核对实际定义、NULL 数据，并评估分拆操作及其数据条件。 |
| FDA-OB-002 | [migration.sql:3](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:3) | AMOUNT NUMBER(10,2) → NUMBER(10,4)：precision 仍为 10，仅 scale 从 2 改为 4。核对旧定义和数据范围，再确认该版本支持的迁移方案。 |
| FDA-OB-003 | [migration.sql:7](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:7)，完整块始于第 4 行 | 对 SALES.CUSTOMER 的目录检查使用 USER_TAB_COLUMNS，但登录用户 OPS 与 current schema SALES 不同。若检查面向 SALES，目录范围可能与目标不一致；应核对对应 ALL_*、显式 owner 条件和目录可见权限。不能据此推定查询会报错或返回 0。 |

三条规则均为 match，对说明中的版本/模式均为 applicable。basis=confirmed 指当前材料内的 SQL、旧定义和身份上下文有证据；其中 FDA-OB-003 按同一计划的 SALES.CUSTOMER 目标解释目录检查，若开发者意在检查 OPS 自身对象，应重新归类。整个计划未发现 ALTER SESSION，未假定外部会话初始化行为。

## 计划身份与来源

- 原样引用 plan.algorithm：flydb-plan-v1。
- 原样引用 plan.id：0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef。
- plan.targetVersion 为 2，表示迁移版本，与数据库版本无关。
- 来源为 [plan.json:7](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/plan.json:7)。[context.md:4](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/context.md:4) 明确说明这是**合成**的 Flydb dry-run 成功信封，不代表真实 Core 生成或数据库执行。未重算或修改计划摘要，原信封保持原样。
- JSON 报告是独立附件，保留五个完整 SQL、原脚本位置、计划 JSON 定位及两份 SQL 文件全文。

## 证据与规则范围

目标身份见 [context.md:2](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/context.md:2)；旧定义见 [before.sql:3](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/before.sql:3) 和 [before.sql:4](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/before.sql:4)。旧定义为用户提供，采集时间、修订与脏状态未知，本次没有实时查询。

规则实际读取自 [Skill 本地方言预检参考](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/skill-snapshots/0.2.0-tested/flydb-analysis/references/compatibility.md:16)，其中引用 [Flydb OceanBase-Oracle 已知限制的固定源码修订](https://github.com/zzxCoding/Flydb/blob/56bc3baef4a4e5c9eefe440644a4343aa960d0a4/docs/getting-started/oceanbase-oracle.md#已知限制)。本次遵照离线要求，**没有访问该网页或厂商文档**。本地说明将其界定为项目实测记录，含 4.2.x 差异及 4.2.1.2 环境，并非厂商全版本兼容承诺。

## 已排除与覆盖

完整读取四份给定材料，并核对成功信封、迁移与语句计数、五个 SQL 文本及起始行。DECLARE…END 作为一个完整块检查，没有按内部分号拆断。未发现动态 SQL、待展开占位符或明显脱敏 SQL。

- [第 1 行注释](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:1) 中的 USER_TAB_COLUMNS 和 MODIFY…NOT NULL 不构成执行语句。
- [第 9 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:9) 只把长度改为 64，没有在同句修改可空性，不命中 FDA-OB-001。
- [第 10 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/compatibility/migration.sql:10) 是 SELECT 中的普通字符串示例，没有动态执行，字符串中的 DDL 不计为命中。

上述排除只针对本次三条规则，不能证明语句在所有条件下可执行。分析状态 completed 仅表示用户限定的离线材料已处理。

## 静态材料无法确定的条件

1. 目标实例的真实版本/模式和待迁移列现状是否与说明、旧 DDL 一致。
2. CUST_TYPE 是否存在 NULL，AMOUNT 现有值是否适合新定义，以及依赖和运行条件。
3. 目录检查的具体意图、OPS/SALES 实际对象、目录可见权限和查询返回值。
4. 前序语句是否执行成功、执行器是否继续、实际会话初始化及执行时状态。
5. 三条内置规则以外的方言限制与完整兼容性；引用规则没有实时核验。

最小补查是提供带目标身份和采集时间的版本、会话、列定义与必要数据条件证据，再在另行授权的验证环境核验具体方案。本次没有连接数据库、执行 SQL、修改输入或生成替代迁移。
