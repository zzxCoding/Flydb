# 离线 Schema 快照与漂移比较

已完成用户限定的三个输入文件分析。在提供的两份完整 `SALES.CUSTOMER` 建表定义中，确认 **4 项结构差异**：新增 `EMAIL`、移除 `LEGACY_TAG`、`CUST_TYPE` 从 `VARCHAR2(16)` 变为 `VARCHAR2(32)`，并从允许空值变为 `NOT NULL`。

这些是基线文件到观测文件的差异。采集时间和对应环境未知，不能据此声称当前数据库或生产环境已经发生漂移。没有具体变更方案不妨碍本次材料比较。

## 产物与格式

- [基线快照 JSON](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/old_skill/outputs/baseline.snapshot.json)
- [观测快照 JSON](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/old_skill/outputs/observed.snapshot.json)
- [漂移 JSON](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/old_skill/outputs/drift.json)
- [快照格式说明](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/old_skill/outputs/snapshot-format.md)
- [检查结果](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/old_skill/outputs/validation-result.json)

指定 Skill 版本未定义专门的 Schema 快照或漂移协议。两份快照使用本次公开说明的 `offline-schema-snapshot-v1` 格式；漂移 JSON 复用该 Skill 的 `flydb-impact-report-v1` 报告约定，并扩展 `comparison`、`changeKind`、`before`、`after`。这些扩展不宣称是 Flydb 的标准契约。

## 范围与来源

材料均为用户提供的 Oracle DDL，`mode=default`，版本未知；不加引号的标识符按范围说明折叠为大写。catalog、实例身份、采集时间未知。[范围说明](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/context.md:2)

两份材料均给出 `SALES.CUSTOMER` 的完整建表定义，但都不能代表整个 schema。基线包含一个视图定义，观测没有导出任何视图；观测包含一个索引定义，基线没有索引导出。[完整性与导出范围](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/context.md:3)

仓库 HEAD 为 `56bc3baef4a4e5c9eefe440644a4343aa960d0a4`；这三个输入位于未跟踪目录中，不能用 HEAD 代表其内容版本，实际输入 SHA-256 记录在快照和检查结果中。没有数据库连接，没有执行 SQL，也没有修改输入。

## 确认的差异

所有条目的依据级别均为 `confirmed`，适用范围仅为两份提供的文件；比较方向为 baseline → observed。

| 编号 | 对象 | 基线 | 观测 | 可确定的结论 |
|---|---|---|---|---|
| D001 | `SALES.CUSTOMER.EMAIL` | 完整表定义中无此列 | `VARCHAR2(128)`，未写可空性子句 | 观测定义增加该列 |
| D002 | `SALES.CUSTOMER.LEGACY_TAG` | `VARCHAR2(8)`，未写可空性子句 | 完整表定义中无此列 | 观测定义移除该列 |
| D003 | `SALES.CUSTOMER.CUST_TYPE` | `VARCHAR2(16)` | `VARCHAR2(32)` | 声明长度参数由 16 变为 32；长度单位没有提供 |
| D004 | `SALES.CUSTOMER.CUST_TYPE` | `NULL` | `NOT NULL` | 观测定义要求非空；真实数据是否满足条件未知 |

证据为 [baseline.sql 第 1–5 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/baseline.sql:1)、[observed.sql 第 1–5 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/observed.sql:1) 和范围说明。JSON 中对应 `E-BASE-TABLE`、`E-OBS-TABLE`、`E-CONTEXT`，每项均有完整来源定位。

`ID` 在两份定义中均为第一列，类型和约束都是 `NUMBER(10) NOT NULL`。没有证据表明 `LEGACY_TAG` 重命名为 `EMAIL`，因此分别记录列删除与列新增。没有推断运行中业务故障或真实数据丢失。

## 覆盖与材料缺失

三个输入文件全部读取：`context.md` 7 行、`baseline.sql` 6 行、`observed.sql` 6 行。两份表定义中的全部列、顺序、类型、可空性、显式默认表达式及表级约束均已检查；读取比例不代表数据库或应用依赖覆盖率。

| 范围 | 已读事实 | 尚不能确定 | 最小补充材料 |
|---|---|---|---|
| 视图 | 基线 `SALES.V_CUSTOMER` 引用 `CUSTOMER.ID`、`CUSTOMER.CUST_TYPE` | 观测是否仍有该视图、是否发生变化或删除 | 同一范围的观测视图清单和定义，以及导出完整性说明 |
| 索引 | 观测 `SALES.IDX_CUSTOMER_TYPE` 引用 `CUSTOMER.CUST_TYPE` | 基线是否已有该索引、索引是否新增或变化 | 基线索引清单和定义，以及导出完整性说明 |
| 当前数据库 | 两份文件的基线/观测角色已知 | 所属实例、环境、时间及当前状态 | 两份材料的采集记录；需实时结论时再取得获授权的结构读取结果 |
| 整个 schema | 仅给定表完整，其他对象导出不完整 | 其他表、视图、索引、触发器等是否发生变化 | 完整对象清单、定义及按对象类型划分的采集范围 |
| 运行与应用影响 | 未提供数据、应用代码或 Oracle 版本与长度配置 | 变更执行可行性、数据兼容性和业务影响 | 仅在需要此类判断时补充版本、配置、数据核验及应用材料 |

视图和索引的读取证据分别是 [baseline.sql:6](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/baseline.sql:6) 与 [observed.sql:6](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/observed.sql:6)。这些是各自材料中的存在证据，缺少另一侧导出时不生成对象增删结论。

## 检查与分析记录

`analysisStatus=completed` 表示用户限定的材料比较已处理完毕，不是迁移放行结论。后续若只需材料层面的结构对照，现有产物即可复用；若要获得实时漂移结论，应先补充环境身份、采集时间和对称完整的对象范围。

使用指定 `flydb-analysis` Skill `0.1.0-draft.2`，宿主 Codex，具体模型标识 `unknown`。本地 Python `3.14.6` 脚本采用限制语法提取，完整处理两个 SQL 文件的所有行；不连接数据库，也不执行输入 SQL。

检查通过：JSON 可解析、报告必需字段和枚举合法、id 唯一、证据引用及实际行号可解析、输入摘要在生成与检查期间保持不变。上述检查不能认证数据库兼容性或业务语义。
