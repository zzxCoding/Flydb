# flydb-schema-snapshot-v1

首版交换格式，用于模型整理事实与脚本比较。它不属于 Flydb Plan 或 CLI 协议。取到什么就记录什么，不能为满足格式填入猜测。

```json
{
  "schemaVersion": "flydb-schema-snapshot-v1",
  "source": {
    "label": "测试库结构文件", "kind": "ddl", "location": "db/schema.sql",
    "environment": "test", "capturedAt": null, "revision": null
  },
  "database": {
    "product": "OceanBase", "mode": "Oracle", "version": "4.2.1.2",
    "identifierPolicy": "upper-unquoted"
  },
  "scope": [
    {"catalog": null, "schema": "SALES", "kind": "column", "parent": "CUSTOMER", "names": null, "status": "complete", "limitations": []}
  ],
  "objects": [
    {
      "catalog": null, "schema": "SALES", "kind": "column", "parent": "CUSTOMER", "name": "CUST_TYPE",
      "properties": {"dataType": "VARCHAR2(16)", "nullable": true, "default": null},
      "unknownProperties": [],
      "evidence": [{"path": "db/schema.sql", "lineStart": 3, "lineEnd": 3}]
    }
  ]
}
```

## 身份和范围

- 对象唯一身份为 catalog/schema/kind/parent/name 五元组。kind 为 table、column、view、procedure、trigger、index、constraint。column 的 parent 是所属表；其他类型 parent 为 null，所属表等关系放到 properties。catalog 为 null 表示没有该命名层；不清楚是否存在该层时在 database.identifierPolicy 和来源限制中记录。
- 名称存数据库实际名称，保留带引号标识符的实际大小写。只有明确的产品规则才能把未加引号名称折叠成大写/小写。identifierPolicy 为 upper-unquoted、lower-unquoted、exact 或 unknown；exact 需要有精确名称来源，并非默认猜测。产品/模式未知可为 null；没有兼容模式的产品填 mode=default。
- scope 每项覆盖一个 catalog/schema/kind/parent；names=null 是该范围全部名称，数组是明确的名称子集。status 为 complete、partial 或 unavailable，说明的是此次来源内的列举范围；limitations 写具体缺口。complete 才能用于证明范围内某对象未出现。不同 scope 不得重叠，以免完整性自相矛盾。
- 每个对象都要落在一个可读取的 scope 内。只给一张表的 DDL，可把该表 names 子集及其 column parent 范围标完整；不能扩大到整个 schema。某个对象列表完整，仍可能只有部分属性已知。

## 属性和证据

properties 只包含已知属性；JSON null 表示确定没有该值，例如没有 DEFAULT。未知属性写入 unknownProperties，不能用 null 代替未知。两边缺少属性的比较结果为未知，而非删除属性。

推荐属性：table 的类型/注释；column 的 dataType、nullable、default、ordinal；view/procedure 的 definition；trigger 的 table、events、definition；index 的 table、columns（保持顺序）、unique、definition；constraint 的 table、columns、constraintType、referencedTable、referencedColumns。definition 保存实际定义，不自动删除空白或改写字符串；其文本变化需要模型复查语义。

evidence 至少一项，可以是 `path/lineStart/lineEnd`，也可以是 `tool/locator`。位置来自实际读取，locator 可为本地工具结果文件和记录位置。source 记录整个材料来源，详细字段未知使用 null；不把整理快照的时间冒充数据库采集时间。

按对象身份去重。多个来源有冲突时生成各自快照并保留来源差异，不能静默覆盖。脚本不解析 DDL，提取准确性仍由模型回查原始材料负责。
