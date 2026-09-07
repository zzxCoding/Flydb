# 提供的目标信息
目标是 OceanBase 4.2.1.2 Oracle 模式，登录用户 OPS，current schema SALES。
before.sql 是用户提供的旧定义，并非本次实时查询；本次只有离线材料。
plan.json 是合成的 Flydb dry-run 成功信封，用于计划消费和 id 透传测试，不代表真实 Core 生成或数据库执行。
这里没有连接凭据。只输出分析，不运行 SQL。
