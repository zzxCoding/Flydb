# 数据库对象依赖

输入可以只有对象或 schema 范围，无需提供改动方案。默认查询谁依赖目标；用户需要目标所依赖的对象时反向追查，并标清方向。

1. 确认 catalog/schema/对象类型/名称，区分表与列级问题。读取 [引用调查](investigation.md) 的 SQL 与数据库对象段落，按用户范围取得定义或快照。
2. 先读直接引用，再追传递依赖。View 看查询、别名及 SELECT *；Procedure 看读写目标及动态 SQL；Trigger 同时记录触发所属表和正文读写；Index 看所属表、列顺序与表达式；Constraint 看本地列与引用列。工具只给对象级边时保留对象级，不能升级为列级。
3. 建立去重的 nodes 与 edges，每条边方向为“使用者 → 被使用对象”，包含关系类型、依据级别、证据。对循环保留边但不重复展开已访问节点。动态对象名能从常量/白名单还原时继续追，不能穷举时记录具体入口。
4. 输出 task=dependencies 的报告；展示“目标 ← 直接使用者 ← 间接使用者”及每条路径的证据。小范围可用表格，图仅在更清楚时添加；结构化 JSON 按 [报告约定](report.md)。

完成条件：声明范围内相关候选已处理或有具体缺口；View/Procedure/Trigger/Index 的已查/缺失范围分别可见。不存在定义材料的对象类型标为 unavailable，不能据此宣布不存在依赖。

示例关系：`V_CUSTOMER -> CUSTOMER.CUST_TYPE`（reads）、`P_EXPORT -> V_CUSTOMER`（reads）、`TRG_CUSTOMER -> CUSTOMER`（attached-to）、`IDX_CUSTOMER_TYPE -> CUSTOMER.CUST_TYPE`（indexes）。依赖事实本身不证明某个版本会自动更新定义、拒绝 DDL 或使对象失效。
