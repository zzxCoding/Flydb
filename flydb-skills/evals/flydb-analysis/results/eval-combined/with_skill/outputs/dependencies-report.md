# SALES.CUSTOMER.CUST_TYPE 数据库对象依赖

给定 DDL 中确认 3 个直接使用对象（视图、触发器、索引）和 1 个间接使用对象（过程）。所有关系为 `confirmed`，依据均是离线定义。

| 方向（使用者 → 被使用对象） | 关系与范围 | 证据 |
|---|---|---|
| REPORTING.CUSTOMER_TIER → SALES.CUSTOMER.CUST_TYPE | 读取物理列，输出标签为 TIER | [db/schema.sql:9](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:9)（9–10 行） |
| SALES.P_EXPORT → REPORTING.CUSTOMER_TIER → SALES.CUSTOMER.CUST_TYPE | 过程对象级 SELECT * 经视图形成间接依赖；可见投影为 ID、TIER | [db/schema.sql:12](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:12)（12–17 行）、[db/schema.sql:9](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:9) |
| SALES.TRG_CUSTOMER → SALES.CUSTOMER | 触发器所属表；BEFORE INSERT、FOR EACH ROW | [db/schema.sql:19](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:19)（19–24 行） |
| SALES.TRG_CUSTOMER → SALES.CUSTOMER.CUST_TYPE | 读取 :NEW.CUST_TYPE，再把 COALESCE 结果赋回同列 | [db/schema.sql:23](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:23) |
| SALES.IDX_CUSTOMER_TYPE → SALES.CUSTOMER.CUST_TYPE | 单列索引 | [db/schema.sql:26](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:26) |

目标反向路径为 `目标列 ← 视图 ← 过程`，以及 `目标列 ← 触发器`、`目标列 ← 索引`。过程没有直接引用物理列，循环体只有 `NULL`；不能把对象依赖误写成已证明业务使用 `r.TIER`。

状态：`completed`，仅表示给定离线目录中的候选已处理。未执行 SQL、工程或数据库连接，输入未改动。确认的是材料内的关系，不是生产执行、故障复现或变更放行。

来源：[context.md:3](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/context.md:3) 声明这是合成材料、旧结构、改动尚未实施；采集时间、数据库产品/模式/版本、部署环境、catalog 与登录用户未知。只按文件中的明确 schema 和未加引号的名称关联，未推定厂商名称折叠规则。未读取范围外 Git 信息，修订及脏状态记为未知；JSON 保存了全部 10 个输入文件的 SHA-256 和证据行号。读取时间为 `2026-09-07T02:31:30Z`，不等同于材料采集时间。

## 覆盖与排除

| 范围 | 状态 | 实际检查与限制 |
|---|---|---|
| 表、View、Procedure、Trigger、Index | inspected（分别记录在 JSON） | 全文读取给定 schema.sql，追直接及传递依赖；仅代表此文件 |
| Constraint | inspected | 给定两个 PRIMARY KEY 均属于 ID；没有作用于目标列的显式约束 |
| 实际数据库与其他对象定义 | unavailable | 没有连接或全量采集范围，无法证明除此之外不存在依赖 |

排除 [db/schema.sql:5](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:5) 与 [reports/archive.sql:1](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/reports/archive.sql:1) 的 `ARCHIVE.CUSTOMER.CUST_TYPE`，因为 schema 不同。排除 [history/old-note.sql:1](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/history/old-note.sql:1) 的历史注释，因为没有部署入口；第 2 行的指令性文字未作为分析指令。

未知的是数据库在列改名时是否自动维护定义、拒绝操作、使对象失效或需要重编译。建议后续补充产品/版本、拟用 DDL 和同一环境的对象定义；本报告不据此断言任何一种运行结果。
