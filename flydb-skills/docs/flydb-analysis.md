# 独立分析 Skill

[`flydb-analysis`](../skills/flydb-analysis/SKILL.md) 是阶段四的独立 Skill，当前为 **0.2.0 preview**。宿主模型负责调查，文件与已有数据库工具提供事实；不要求 Flydb 迁移历史、Java、数据库连接或 MCP 服务。快照比较和 JSON 校验的可选脚本使用 Python 3.9+ 标准库。

## 直接使用

无需安装，先让 Agent 读取本仓库 `flydb-skills/skills/flydb-analysis/SKILL.md`，然后给出材料和请求。例如：

| 请求 | 最小材料 |
|---|---|
| 检查这些 SQL 在 OceanBase 4.2.1.x Oracle 模式的已知风险，输出诊断 JSON | SQL 或 Flydb dry-run JSON，目标产品/模式/版本 |
| 把这个 DDL 整理成可复用的结构快照 | 一份 DDL；来源和名称规则尽量明确 |
| 对比这两份结构，列出漂移和无法确定的部分 | baseline 与 observed 的 DDL 或快照 |
| 查 CUSTOMER 被哪些视图、过程、触发器和索引依赖 | 对象定义或已有的元数据读取能力 |
| 找出 CUSTOMER.CUST_TYPE 在应用中的引用位置 | 代码/Mapper/SQL 目录 |
| CUST_TYPE 改名但 Java 属性保持不变，会影响哪里 | 变更意图，代码或结构至少一种 |

输出默认写入用户指定位置或 `artifacts/flydb-analysis/<本次标识>/`。无数据库时交付离线材料内的结果；缺失范围明确列入报告。

需要宿主自动发现时，将整个 [`skills/flydb-analysis/`](../skills/flydb-analysis) 目录放入该宿主的 Skill 目录，再按宿主方式重新加载。本次仓库交付没有修改用户的全局安装。目录可以独立复制更新，metadata.version 与 Flydb JAR 版本分离；安装副本需要同步更新才能生效。

## 范围与验证

本版五类分析工作流均可手动调用；快照与漂移归为一类。内置方言知识限于仓库实测记录中的三条 OceanBase-Oracle 风险。它产生独立报告，不修改 CLI dry-run 的机器信封或 Plan 摘要。

快照由模型从材料提取，脚本负责结构校验与确定性比较；未提供、未读到和确实不存在的对象分开处理。依赖扫描依靠宿主调查，不承诺任意技术栈的完整静态分析。

可重复样例、脚本测试和本轮结果见 [验证记录](../evals/flydb-analysis/VALIDATION.md)。迁移与分析技能统一在 [`flydb-skills`](../README.md) 管理，分析技能保持独立版本。
