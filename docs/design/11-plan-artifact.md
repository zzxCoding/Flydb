# 11 Plan Artifact v1：dry-run 计划的结构化表示

> [← 10 机器契约](10-machine-contract.md) | [返回总览](00-overview.md)

**本文档是契约**：标记为契约的章节不能静默偏离；实现与文档冲突时，先按 [AGENTS.md](../../AGENTS.md) §3 处理冲突，再决定修代码或更新契约。使用者视角的 schema 见[JSON 输出参考](../reference/json-output.md)。

## 1. 定位与问题

Plan Artifact v1 回答一个问题：**人、CI 与 Agent 如何指称并核对“同一份迁移计划”**。

- 人看文本 dry-run 输出，CI 消费 JSON 信封，Agent 通过 MCP `flydb_plan_migrate` 取得计划——三者消费的是同一份计划，而不是三份各自渲染的近似描述。
- 计划必须有确定性身份：同一脚本集合、同一顺序、同一迁移元数据、checksum 与占位符解析后的实际 SQL，必然得到同一 `plan.id`；任何一项变化必然得到不同 `plan.id`。这使得“我批准的就是这份计划”成为可验证的陈述，是阶段五 Plan → Validate → Approval → Apply 协议的直接前置。

阶段三的边界（契约）：

1. **Artifact 由 Java Core 产生**（`flydb-core` 的 `PlanArtifact`），CLI 只渲染；TypeScript MCP Adapter 或任何外部宿主不得从 dry-run 字段推导第二套计划模型。
2. **v1 载体是 dry-run 成功信封本身**：不新增命令、不新增文件格式；信封新增 `plan` 对象与 `migrations[]` 标识字段，全部为 `protocolVersion=1` 内的追加字段。
3. **摘要算法是 `flydb-plan-v1`**；算法演进时换用新 token 与新字段，不修改 v1 语义。

## 2. 载荷 schema（契约）

`--json --dry-run migrate` / `--json --dry-run undo` 成功信封在机器契约（[10](10-machine-contract.md)）基础上追加：

```json
{"protocolVersion":1,"command":"migrate","status":"success","exitCode":0,
 "dryRun":true,
 "plan":{"algorithm":"flydb-plan-v1","direction":"migrate",
         "id":"bf5b19854acd952093cd18485c33c174b48bb1a3077de5cd18af23b17400650f",
         "targetVersion":"2","migrationCount":1,"statementCount":2},
 "migrations":[{"script":"V2__add_order.sql","type":"SQL","version":"2",
                "description":"add_order","checksum":777,"statementCount":2,
                "statements":[{"lineNumber":1,"sql":"SELECT 0"},
                              {"lineNumber":2,"sql":"SELECT 1"}]}]}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `plan.algorithm` | 字符串 | 恒为 `flydb-plan-v1`；变更即为新算法 |
| `plan.direction` | 字符串 | `migrate` 或 `undo` |
| `plan.id` | 字符串 | 规范文本的 SHA-256，64 位小写十六进制 |
| `plan.targetVersion` | 字符串或 null | 计划中最后一个版本化迁移的版本；计划为空或只含可重复迁移为 null |
| `plan.migrationCount` | 整数 | 计划内迁移数 |
| `plan.statementCount` | 整数 | 全部语句数之和 |
| `migrations[].version` | 字符串或 null | 可重复迁移为 null |
| `migrations[].description` | 字符串或 null | 迁移描述 |
| `migrations[].checksum` | 整数或 null | 解析出的 checksum |
| `migrations[].statementCount` | 整数 | 该迁移语句数 |

文本模式 dry-run 同步打印 `计划 flydb-plan-v1/<id>` 摘要行；即使指定 `--quiet`，该审计标识也不会被隐藏，人与机器指称同一标识。

## 3. 摘要算法（契约）

`plan.id` 是以下规范文本（UTF-8）的 SHA-256：

```
flydb-plan-v1\n
direction\t<field(direction)>\n
[migration\t<field(version)>\t<field(type)>\t<field(script)>\t<field(description)>\t<field(checksum)>\t<statementCount>\n
[statement\t<lineNumber>\t<field(resolvedSql)>\n]*
]*
```

- `field(value)` 编码为 `<UTF-8字节数>:<原值>`；null 编码为 `-1:`。长度前缀使字段中的制表符、换行和多字节字符均无歧义。
- 每个迁移先写一条 `migration` 记录，再按执行顺序写入每条 `statement` 记录；`resolvedSql` 是占位符替换后的实际 SQL，`lineNumber` 是切分后的起始行。
- `type` 是 `MigrationType` 枚举名（`SQL`、`JDBC`、`BASELINE`、`UNDO_SQL`），与信封 token 一致。
- 规范文本**不含**时间戳、绝对路径、执行耗时或数据库状态；只含决定计划内容本身的字段。

确定性与敏感性（契约，由 `PlanArtifactTest` 固定）：

- 同一输入（脚本、顺序、迁移元数据、checksum、语句切分、实际 SQL）必然得到同一 `plan.id`；
- 顺序变化、checksum 变化、方向变化必然得到不同 `plan.id`；
- 语句切分结果与每条实际 SQL 均参与摘要：占位符值变化或 SQL 文本变化时，即使脚本 checksum 和语句数未变，也会得到不同 `plan.id`。

## 4. 实现与消费边界

- 产生：`flydb-core` `api.PlanArtifact.of(DryRunResult)` 单一实现；`DryRunMigration` 携带 `version/description/checksum`，`DryRunResult` 携带 `direction`。core 保持零第三方依赖（SHA-256 用 JDK `MessageDigest`）。
- 渲染：`flydb-cli` `JsonRenderers.dryRun` 与文本 `printDryRun`；信封字段顺序固定，精确字符串断言见 `JsonRenderersTest`。
- 消费：CI 直接 `jq .plan.id`；MCP `flydb_plan_migrate` 工具透传信封（含 `plan`），Adapter 不重算、不改写（见 MCP 工具参考）。
- 阶段五的审批协议将引用 `plan.id` 作为审批对象；写入工具如何消费 Artifact 属阶段五设计，不在 v1 范围。
