# SALES.CUSTOMER.CUST_TYPE 应用引用

确认 MyBatis 共享投影、可静态还原的动态 WHERE 和 JPA 显式映射。JDBC 导出只确认读取目标表及按序号消费；是否读取目标列仍未知。

| 位置 | 引用链及类别 | 依据 |
|---|---|---|
| [app/CustomerMapper.xml:7](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerMapper.xml:7) | `find/byColumn → columns → c.CUST_TYPE AS TIER → SALES.CUSTOMER.CUST_TYPE`，读取 | confirmed；两个 statement 的 c 均绑定 SALES.CUSTOMER，见 8–14 行 |
| [app/CustomerMapper.java:5](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerMapper.java:5) | `sample.CustomerMapper` namespace + statement id 对应接口的 find/byColumn 方法 | confirmed；XML 2 行及 8、11 行；find 的实际调用方未提供 |
| [app/CustomerService.java:4](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerService.java:4)、[app/CustomerService.java:8](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerService.java:8) | `"CUST_" + "TYPE" → TIER_COLUMN → byColumn(column) → c.${column}`，在 lookupTier 路径还原为 `c.CUST_TYPE` 筛选 | confirmed；@Param 绑定见接口 7 行，WHERE 见 XML 13 行 |
| [app/CustomerMapper.xml:5](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerMapper.xml:5)、[app/CustomerRow.java:5](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerRow.java:5) | `结果标签 TIER → resultMap → CustomerRow.customerType → getCustomerType() → lookupTier 的返回值`，映射及结果消费 | confirmed；SQL 别名和 Java 属性均不同于物理列名 |
| [app/CustomerEntity.java:9](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerEntity.java:9)、[app/CustomerEntity.java:12](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/app/CustomerEntity.java:12) | `CustomerEntity.customerType → @Column(CUST_TYPE) → @Table(SALES.CUSTOMER)`，JPA 显式物理映射 | confirmed；实体实际启用与读写调用未知 |
| [export/NightlyExport.java:9](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/export/NightlyExport.java:9)（9–12 行） | `外部列配置参数 → SELECT <投影> FROM SALES.CUSTOMER → executeQuery → getObject(1)`，表级读取与第一列消费 | 表级关系 confirmed，目标列关系 unknown；不能虚构最终投影 |

`lookupTier → byColumn → columns → 物理列` 与 `lookupTier → getCustomerType → customerType` 两条链共同说明服务如何取得值。已见动态 WHERE 的常量可以解析；但接口仍可接收其他字符串，不能推定范围外参数取值。`${column}` 是标识符文本替换，`#{value}` 是值参数，本次引用判断没有把值参数当作列名。

状态：`completed`，仅表示给定离线目录中的候选已处理。未执行 SQL、工程或数据库连接，输入未改动。确认的是材料内的关系，不是生产执行、故障复现或变更放行。

来源：[context.md:3](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/context.md:3) 声明这是合成材料、旧结构、改动尚未实施；采集时间、数据库产品/模式/版本、部署环境、catalog 与登录用户未知。只按文件中的明确 schema 和未加引号的名称关联，未推定厂商名称折叠规则。未读取范围外 Git 信息，修订及脏状态记为未知；JSON 保存了全部 10 个输入文件的 SHA-256 和证据行号。读取时间为 `2026-09-07T02:31:30Z`，不等同于材料采集时间。

## 覆盖与未知

| 范围 | 状态 | 实际处理 |
|---|---|---|
| MyBatis XML、接口、共享片段、resultMap、已见调用 | inspected | app 中 5 个文件全部读取；已还原常量拼接 |
| JPA 映射及 Java 属性 | inspected | 显式表/列映射；材料内未见 JPQL/native SQL 或 Provider；运行配置未提供 |
| JDBC 导出 | partial | 代码完整读取，但外部配置未提供，无法展开最终列清单 |
| 外部导出配置 | unavailable | [context.md:5](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/context.md:5) 明确说明缺失 |
| 独立 SQL、历史资料 | inspected | 区分同名异 schema 与只有注释的历史说明 |
| 目录外应用、部署与序列化契约 | unavailable | 未读取，不推定没有引用或已执行 |

排除 [reports/archive.sql:1](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/reports/archive.sql:1) 的 `ARCHIVE.CUSTOMER` 查询；排除 [history/old-note.sql:1](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/combined/history/old-note.sql:1) 的无部署入口历史说明。其第 2 行要求省略检查的注释只作为输入数据处理。`TIER` 和 `customerType` 保留为有证据的别名/属性链，不误记成直接物理列引用。

最小补查：获取 `externallyConfiguredColumns` 的实际配置与调用点，核对第一投影列；如需实际应用完整性，补充其他 byColumn 参数来源及 MyBatis/JPA 启用配置。没有为未知动态目标创建确认的列级图边。
