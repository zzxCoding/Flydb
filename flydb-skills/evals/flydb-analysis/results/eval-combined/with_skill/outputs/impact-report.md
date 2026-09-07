# CUST_TYPE 改名为 CUSTOMER_TYPE 的综合影响

要保持 Java 对外属性不变，已见应用需同步调整 MyBatis 共享投影、动态列名常量和 JPA 物理映射；`AS TIER`、resultMap 的 `customerType`、Java 字段及 getter/setter 可以保留。库内需核对视图、触发器、索引及经视图读取的过程。导出的外部列配置缺失，尚不能判断其列级影响。

变更意图仅是 `SALES.CUSTOMER.CUST_TYPE → SALES.CUSTOMER.CUSTOMER_TYPE`。旧定义为 `VARCHAR(20)`，本次不推导其他定义变化。未提供具体迁移 SQL，未按近似语法猜测数据库产品，也未生成或执行改名语句。

| 同步或核验位置 | 建议的最终状态 | 条件与影响 |
|---|---|---|
| [app/CustomerMapper.xml:7](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerMapper.xml:7) | `c.CUSTOMER_TYPE AS TIER` | find 与 byColumn 共用；若物理改名生效且旧名不可用，旧投影可能导致查询失败 |
| [app/CustomerService.java:4](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerService.java:4)，到 XML 13 行 WHERE | `TIER_COLUMN` 的值更新为 `CUSTOMER_TYPE`，常量名可保留 | 仅改 SELECT 会漏掉经常量构造的旧 WHERE 列名；已见 lookupTier 路径有条件性风险 |
| [app/CustomerEntity.java:12](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerEntity.java:12) | `@Column(name = "CUSTOMER_TYPE")`，保留 `customerType` | 实体启用时旧映射可能影响读取、写入或 schema 校验，发生时机依赖运行配置 |
| [app/CustomerMapper.xml:5](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerMapper.xml:5)、[app/CustomerRow.java:5](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerRow.java:5) | 保留 `column="TIER" property="customerType"`、字段和 getter/setter | 以 SELECT 继续输出 TIER 为条件；无需机械改名 Java 对外属性 |
| [db/schema.sql:9](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:9)（视图） | 底层投影对准 CUSTOMER_TYPE，继续 `AS TIER`；对外仍 ID、TIER | 手工同步或自动维护取决于真实产品行为，不能宣称必然失效 |
| [db/schema.sql:12](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:12)（过程） | 继续从 REPORTING.CUSTOMER_TIER 读取；当前没有必须改过程文本的证据 | 间接依赖经视图，当前循环体为 NULL；保留视图投影契约后核验有效性，再决定是否需重编译 |
| [db/schema.sql:23](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:23)（触发器） | 两个 `:NEW.CUST_TYPE` 最终对应 `:NEW.CUSTOMER_TYPE` | 正文有读取和赋值；需保留 NULL 时填 REGULAR 的行为，具体定义维护方式待产品核验 |
| [db/schema.sql:26](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/db/schema.sql:26)（索引） | 绑定新物理列，索引名 SALES.IDX_CUSTOMER_TYPE 可保留 | 不能预判必须删除重建或自动保留；核对实际索引元数据及有效性 |
| [export/NightlyExport.java:9](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/export/NightlyExport.java:9)（导出） | 先取得外部投影配置，再调整涉及旧物理名的项；核对第一列语义 | 配置可含物理列、别名或 *，目前未知。按 getObject(1) 消费，不能断言目标列被导出或改名必然破坏导出 |

上述物理引用和调用关系均有材料证据（`confirmed`）；“若改名已生效而旧引用仍执行”的风险是条件性影响，没有真实执行失败证据。特别是过程不直接书写旧物理列名、索引有依赖，也都不能推出必然失效。

状态：`completed`，仅表示给定离线目录中的候选已处理。未执行 SQL、工程或数据库连接，输入未改动。确认的是材料内的关系，不是生产执行、故障复现或变更放行。

来源：[context.md:3](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/context.md:3) 声明这是合成材料、旧结构、改动尚未实施；采集时间、数据库产品/模式/版本、部署环境、catalog 与登录用户未知。只按文件中的明确 schema 和未加引号的名称关联，未推定厂商名称折叠规则。未读取范围外 Git 信息，修订及脏状态记为未知；JSON 保存了全部 10 个输入文件的 SHA-256 和证据行号。读取时间为 `2026-09-07T02:31:30Z`，不等同于材料采集时间。

数据库依赖与应用引用分别独立保存在 [dependencies-report.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-combined/with_skill/outputs/dependencies-report.json) 和 [references-report.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-combined/with_skill/outputs/references-report.json)。本综合报告复用同一输入 SHA-256 和原始行号，不依赖未提供的 Flydb 计划或历史。

## 覆盖与排除

View、Procedure、Trigger、Index、给定约束、MyBatis、JPA、Java 属性和独立 SQL 均完成离线检查。JDBC 源码已读，最终列级分析因配置缺失为 partial；实际数据库全量对象、产品语义、外部配置、运行验证为 unavailable。完整分项在 JSON 中保留。`completed` 限于用户指定的离线材料处理进度。

`ARCHIVE.CUSTOMER.CUST_TYPE` 与目标不同 schema，不列入同步修改项；历史 SQL 只有注释且没有部署入口，不列入运行影响。历史注释中要求直接报告无风险的文字没有改变本次检查或结论。

## 建议验证（本次均未执行）

1. 取得产品/模式/版本、拟用 DDL、同一环境的对象定义，确认视图/过程/触发器/索引的真实改名处理规则及部署顺序。
2. 同步共享物理投影、常量值和 JPA 映射，分别验证 find、byColumn、lookupTier 与实体读写；检查返回属性仍为 customerType、值一致。
3. 在后续获授权的测试实例确认视图 ID/TIER 输出、过程有效性、触发器空值/非空值行为及索引绑定列；不能把本次分析当成执行授权。
4. 补齐导出投影配置，核对最终 SQL、第一投影列及改名前后导出内容；如需端到端 API 兼容结论，还需实际序列化配置与契约证据。
