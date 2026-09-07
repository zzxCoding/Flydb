两份离线 Schema 快照已生成。确认 **4 条文件结构差异**：CUST_TYPE 的类型与可空性变化、EMAIL 新增、LEGACY_TAG 移除。比较状态为 **partial**，原因是视图、索引等材料缺失；快照整理流程均已完成。

| 确定差异 | baseline → observed | 证据 |
|---|---|---|
| CUST_TYPE.dataType | VARCHAR2(16) → VARCHAR2(32) | [基线第 3 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/baseline.sql:3)、[观测第 3 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/observed.sql:3) |
| CUST_TYPE.nullable | true → false，即 NULL → NOT NULL | [基线第 3 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/baseline.sql:3)、[观测第 3 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/observed.sql:3) |
| EMAIL 新增 | 基线完整列清单无此列；观测为 VARCHAR2(128)，可空、无 DEFAULT、序号 3 | [基线完整建表](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/baseline.sql:1)、[观测第 4 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/observed.sql:4) |
| LEGACY_TAG 移除 | 基线为 VARCHAR2(8)，可空、无 DEFAULT、序号 3；观测完整列清单无此列 | [基线第 4 行](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/baseline.sql:4)、[观测完整建表](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/observed.sql:1) |

增减判定仅使用用户明确声明为完整的 CUSTOMER 建表定义；没有将其扩大成整个 SALES schema 的完整性。[范围与身份依据](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/context.md:2)

ID 的已知属性保持一致：NUMBER(10)、NOT NULL、无 DEFAULT、序号 1。没有改名或变更执行记录，因此 LEGACY_TAG 与 EMAIL 按两个对象的移除和新增记录，不能认定为改名。

材料缺失与结论边界：

- **V_CUSTOMER 是否消失未知。** [基线视图定义](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/baseline.sql:6) 可复用，但 observed 没有导出任何视图，不能判断视图保留、删除或定义变化。最小补查是 observed 的该视图定义或完整视图清单。
- **IDX_CUSTOMER_TYPE 是否新增未知。** [观测索引定义](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/observed.sql:6) 显示它是 CUSTOMER(CUST_TYPE) 上的非唯一索引；baseline 没有索引导出，不能将缺少定义作为新增证据。最小补查是 baseline 的该索引定义或完整索引清单。
- 双方均未给出过程、触发器和独立约束清单。列的 NULL/NOT NULL 已提取，但不虚构数据库生成的约束名称。CUSTOMER 之外的表及其余视图、索引也未完整枚举。
- Oracle 产品和 default 模式、未加引号名称折叠大写规则已知；版本未知。VARCHAR2 未指定 BYTE/CHAR，相关长度语义保留为 unknownProperties，不能将长度参数变化直接解释为字节容量变化。
- 采集时间、环境、是否对应当前数据库及文件修订/脏状态未知；快照保留输入文件 SHA-256。注释、物理存储、权限等未提供的属性未被声明为相同。

上述来源限制由 [context.md](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/fixtures/snapshot-drift/context.md:4) 给出。需要判定当前数据库漂移时，应补充带环境与采集时间、范围一致的元数据导出；当前结果只确认所提供材料的差异。

产物：

- [baseline-schema-snapshot.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/with_skill/outputs/baseline-schema-snapshot.json)：5 个对象，含表、3 列和基线视图。
- [observed-schema-snapshot.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/with_skill/outputs/observed-schema-snapshot.json)：5 个对象，含表、3 列和观测索引。
- [drift-report.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/with_skill/outputs/drift-report.json)：4 条 changes、5 条有证据的 findings、8 组归并后的 unknowns。
- [drift-report.script.json](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/with_skill/outputs/drift-report.script.json)：未经整理的脚本输出，保留 21 条原始缺口。
- [execution-log.md](/Users/xuan/worksapce/Flydb/flydb-skills/evals/flydb-analysis/results/eval-snapshot-drift/with_skill/outputs/execution-log.md)：本次实际文件读取、命令和问题的自述记录。

已运行 Skill 自带的 diff 和 validate；两个快照及两个漂移 JSON 均通过格式校验。关键列、视图、索引已人工回查原始 DDL。格式校验不证明生产状态或运行时兼容性；本次未连接数据库、未执行 SQL，也未修改输入。
