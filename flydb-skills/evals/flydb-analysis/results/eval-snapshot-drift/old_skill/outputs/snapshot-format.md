# 本次离线快照格式

`schemaVersion: offline-schema-snapshot-v1` 是本次交付公开说明的格式，不是指定 Skill 或 Flydb 已发布的快照协议。两份快照使用相同结构，可由 JSON 消费方读取并复查来源。

| 字段 | 含义 |
|---|---|
| `snapshotId` | `baseline` 或 `observed`，表达比较角色，不隐含采集时间 |
| `database` | 已知 Oracle、mode、schema；未知 catalog、version、environment 使用 null |
| `identifierRules` | 未加引号名称折叠规则及范围说明的来源行 |
| `sources` | 输入绝对路径、SHA-256、是否实时、采集时间与版本限制 |
| `scope` | 整个 schema 完整性、各对象集合导出范围、完整表定义的明确名单 |
| `tables` | 表身份、完整性、按声明顺序的列、表级约束和来源行 |
| `views` / `indexes` | 实际读到的对象定义、明确引用和来源行 |
| `interpretation` | 本次规范化与缺失值解释规则 |
| `provenance` | Skill 版本、宿主与实际提取方式 |

对象身份由 catalog/schema/table/column 组成。未知 catalog 是 null，不能凭空映射到其他实例。标识符保存为本次未加引号规则下的大写名称；脚本没有实现通用的 Oracle 引号名称解析器。

列的 `dataType` 保留类型名、数值参数及原声明，未推断省略的字符长度单位。`nullable` 保存通常的 Oracle DDL 声明语义，`nullabilityDeclaration` 同时保存 `NULL`、`NOT NULL` 或 `UNSPECIFIED`，供消费者区分显式与省略声明。`defaultExpression: null` 表示这份完整建表定义中没有显式默认表达式，不能作为查询到的实时数据库值。

只有两侧 `definitionComplete=true` 的同一表，在本次范围说明支持下，才将缺失列解释为材料中的列增删。`views: []`、`indexes: []` 必须结合 `scope` 消费；本次空集合是没有导出材料，不能当成数据库没有这些对象。禁止把未知对象集合转成删除或新增。

漂移 JSON 的基础契约为 `flydb-impact-report-v1`；本次扩展如下：

- `comparison`：基线、观测、比较方向、可比范围和是否实时核验。
- `findings[].changeKind`：`column-added`、`column-removed`、`column-type-changed`、`column-nullability-changed`。
- `findings[].before` / `after`：变化前后的列声明或属性值；列缺失使用 null。
- `unknowns[].id` / `evidenceIds`：定位具体材料缺口与其证据。

仅 `findings` 包含确定差异；缺少对照材料的视图和索引进入 `unknowns`。消费者不得从文件角色推断生产部署顺序或把报告当成执行授权。

`rebuild-and-check.py` 可在同一路径重新生成快照与漂移 JSON，并执行本地结构检查。该脚本仅覆盖本次输入的简单 CREATE 声明，遇到其他语法会停止，不是通用数据库解析器。
