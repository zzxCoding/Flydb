# 分析报告与校验

所有流程共用证据与覆盖语义，快照另见 [快照格式](snapshot-format.md)。默认摘要 + Markdown；用户要机器输出或下游需要复用时，生成以下 JSON。已发布的 CLI/Flydb Plan 不受影响。

## 语义

analysisStatus 为 completed（用户限定范围已处理）、partial（关心的范围有缺失/截断/未读候选）、blocked（该流程最小输入不可辨认）。它描述进度，不是变更放行。未知运行时行为不应被写成已验证。

basis 为 confirmed（当前材料中关系或差异有证据）、inferred（有线索但关键条件待查）。缺乏证据的潜在范围放入 unknowns。确认关系不代表生产会执行，也不代表特定数据库必然报错。

coverage.status 为 inspected、partial、unavailable、not-applicable。not-applicable 需要项目事实支持。声明检查范围、实际处理内容和限制；文件读取数不等于语义依赖覆盖率。

## JSON 公共字段

schemaVersion 固定为 `flydb-analysis-report-v1`；task 为 compatibility、drift、dependencies、references、impact。此版本取代本项目早期草案的通用报告封装，历史 `flydb-impact-report-v1` 产物保持原样。

| 字段 | 约定 |
|---|---|
| request | 用户目标与范围的对象 |
| analysisStatus | 上述进度枚举 |
| sources | id、kind、location；可附 revision、capturedAt、environment、limitations，未知为 null |
| evidence | id、sourceId、location；location 使用快照的文件行号或工具定位格式，可附最小 excerpt |
| findings | id、target（对象或字符串）、claim、basis、impact、conditions（数组）、evidenceIds、nextActions（数组） |
| coverage | scope、status、checked（数组）、limitations（数组） |
| unknowns | gap、affectedClaims（数组）、nextCheck |
| excluded | 已排除候选及理由；无关键排除项为空数组 |
| provenance | skillVersion、host、model；模型不明用 unknown；可附工具版本 |
| sourcePlan | 可选，提供方、algorithm、id、来源定位；原样引用，不重算 |

公共数组即使为空也保留。证据和来源 id 唯一且可互相解析；findings 的证据不能空。

## 流程专用字段

- compatibility：diagnostics，每项 id、code、severity（info/warning/error）、basis、ruleSource、applicability、targetDatabase、statementLocation、evidenceIds、message、nextCheck；ruleCoverage 每项 code、status（match/not-matched/unknown/not-applicable）、reason。诊断默认 warning，不自动输出审批/执行状态。没有命中仍记录已检查规则。
- dependencies / references：nodes 每项 id、kind、label，可附 object/位置；edges 每项 id、from、to、relation、basis、evidenceIds。from 是使用者，to 是被使用者。所有端点必须存在，unknown 动态目标不伪造节点或确认边。
- drift：changes 每项 kind（added/removed/changed）、target、property、before、after、evidenceIds。脚本比较的是快照事实；definition 文本差异、缺失前提的对象差异必须由模型解释。sourcePlan 不参与比较。
- impact：使用 findings 表达影响与条件，可引用其他流程的产物及其中的证据。

## 交付检查

运行 `python3 <skill目录>/scripts/analysis_artifacts.py validate <产物.json>`。工具校验字段、枚举、唯一性、证据引用和图端点；不能判断结论语义，也不认证来源可信。格式错误修正后再次校验。

Markdown 先写最重要结果，再给来源、具体发现/差异/引用链、覆盖与未知项、下一步。报告按任务规模收缩，避免复述全部证据；保留可点击来源位置。未执行的验证使用“建议验证”，不写成已通过。
