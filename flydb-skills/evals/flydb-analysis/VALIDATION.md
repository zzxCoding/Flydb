# flydb-analysis 0.2.0 preview 验证记录

日期：2026-09-07。可使用入口：[SKILL.md](../../skills/flydb-analysis/SKILL.md)，说明与请求示例：[分析指南](../../docs/flydb-analysis.md)。

本版交付五类基础分析流程，由宿主模型调查；快照生成与漂移比较归为一类。17 个合成输入文件、三组复合请求覆盖全部流程，无数据库连接或 Java/MCP 前置。它是可直接手动试用的 preview，不代表第四阶段的正式 CLI 集成与全数据库支持已经验收。

## 已完成的行为试用

| 工作流 | 新版实际结果 | 产物 |
|---|---|---|
| 方言预检 | 读取 5 个计划语句，识别 FDA-OB-001/002/003 三条 warning；排除注释、普通字符串与仅修改长度的误报 | [报告](results/eval-compatibility/with_skill/outputs/compatibility-report.md)、[JSON](results/eval-compatibility/with_skill/outputs/compatibility-report.json) |
| 快照与漂移 | 无变更方案时生成两份快照；确认类型、可空性、列新增和列移除共 4 项差异；未导出的视图/索引保持未知 | [报告](results/eval-snapshot-drift/with_skill/outputs/drift-report.md)、[基线](results/eval-snapshot-drift/with_skill/outputs/baseline-schema-snapshot.json)、[观测](results/eval-snapshot-drift/with_skill/outputs/observed-schema-snapshot.json) |
| 数据库依赖 | 4 项发现、6 条依赖边，覆盖 View、Procedure 经 View 的间接引用、Trigger 读写和所属表、Index 列 | [报告](results/eval-combined/with_skill/outputs/dependencies-report.md)、[JSON](results/eval-combined/with_skill/outputs/dependencies-report.json) |
| 应用引用 | 5 项发现、22 条引用边，覆盖 MyBatis、JPA、常量构造列名和 JDBC；导出列配置保持未知 | [报告](results/eval-combined/with_skill/outputs/references-report.md)、[JSON](results/eval-combined/with_skill/outputs/references-report.json) |
| 综合影响 | 9 项影响；保留 TIER、customerType 和 Java getter/setter；过程/索引的数据库行为保持条件性 | [报告](results/eval-combined/with_skill/outputs/impact-report.md)、[JSON](results/eval-combined/with_skill/outputs/impact-report.json) |

新版本的 8 个流程 JSON（含两个快照与未整理的原始漂移输出）通过随包校验器。文件证据位置经回查；计划 algorithm/id 与合成输入一致。合成 plan.id 仅检验透传，不证明 Core 摘要生成或真实目标绑定。

## 对照方法与结果

[请求及断言](evals.json) 在执行时与受测 Agent 隔离。每个场景的新旧版本由不同、无对话历史的 Agent 执行；共 6 次初始试用。受测版本：[新版冻结副本](skill-snapshots/0.2.0-tested/flydb-analysis/SKILL.md)、[旧版 0.1.0-draft.2](skill-snapshots/0.1.0-draft.2/flydb-analysis/SKILL.md)。每个 Agent 只得到指定 Skill、对应输入与输出位置。

| 场景 | 新版 | 旧版 |
|---|---:|---:|
| compatibility | 5/5 | 5/5 |
| snapshot-drift | 5/5 | 5/5 |
| combined | 5/5 | 5/5 |

主 Agent 对照原始材料回读报告、JSON 发现、引用链与条件，逐项评分保存在对应配置的 grading.json。两版都通过这 15 项有限语义检查，不能声称新版普遍提高准确率。新版的具体交付增量是可重复的工作流、三条有来源的方言规则、统一 JSON 结构与快照比较脚本；旧版对新流程临时创建了专用格式或局部脚本。

[对照页面](review.html) 可阅读六次输出与评分；[统计数据](benchmark.json) 只汇总语义断言。没有可独立核验的精确模型标识、完整耗时或 Token 用量，相关指标省略，不用字符数替代。每场景每版只有一次，跨场景统计不能解释为重复运行的稳定性。

## 试用后修正

1. 原始快照比较产生重复的范围缺口。最终脚本合并相同缺口并保留 affectedClaims 中的具体 scope；[重放结果](final-helper-replay.json) 从 21 条整理为 7 条，4 项差异与 partial 状态不变。已增加对应回归检查。
2. 新版兼容性与漂移报告的部分 evidence.excerpt 是释义。已在公共报告约定中明确“excerpt 使用逐字原文，释义放 claim/message”，由原试用 Agent 修正两份报告并重新检查；只改了摘录值，结论、计划身份、差异与快照均不变。[修正前兼容性输出](before-excerpt-review/compatibility/compatibility-report.json) 和 [修正前漂移输出](before-excerpt-review/snapshot-drift/drift-report.json) 保留用于审计。最终报告不能描述成完全无需反馈的一次成稿。
3. 明确材料接入参考按所选流程判定最小输入，快照/漂移不要求拟议变更。新版入口本身已有此路由；该修正去除了旧参考的歧义。

冻结副本保留初次试用内容。最终目录与它的差异限于上述脚本缺口整理、摘录说明和最小输入澄清；脚本修改已用实际生成的两份快照重放，通用报告字段没有改变。

## 本地检查

```bash
python3 -m unittest discover -s flydb-skills/evals/flydb-analysis -p 'test_*.py' -v
python3 flydb-skills/skills/flydb-analysis/scripts/analysis_artifacts.py validate <产物.json>
python3 flydb-skills/skills/flydb-analysis/scripts/analysis_artifacts.py diff <baseline.json> <observed.json> --out <新的报告.json>
```

- 16 项脚本测试通过：完整/不完整范围、未知属性与已知 null、名称大小写、异构产品、范围不对齐、证据和图端点、重复键、输入保留与拒绝覆盖等。
- 实际执行环境为 Python 3.14；另检查 Python 3.9 语法。辅助脚本仅用标准库，Skill 本身不要求 Python。
- Skill 格式校验、JSON 解析、本地文档链接、证据行号及空白检查通过。
- 17 个原始输入在六次试用后 SHA-256 与 [初始清单](input-manifest.json) 一致。
- 独立 .skill 包经解压，11 个文件与当前 Skill 目录逐字节一致；解压后的脚本完成离线快照比较。没有进行全局安装或对外发布。

初次归档将临时输出/受测 Skill 路径替换为仓库内对应归档路径。2026-09-07 统一迁入 `flydb-skills/` 时，再将输入、受测副本和输出路径更新到新目录，并按移动后的快照文件重算报告中的 `snapshotSha256`。17 个输入文件的内容、快照结构事实、分析结论和评分保持不变；这次路径整理不计为新一轮模型评测。执行日志是 Agent 自述，其中路径随归档位置更新，不替代完整工具审计；原始 shell here-document 临时文件失败及后续成功重试在日志中保留。

迁移后复核：16 项脚本测试、4 项插件结构测试与 Skill 格式校验通过；331 个相关本地链接有效。37 个归档 JSON 除路径与对应快照摘要外语义一致，14 个当前格式产物通过校验，17 个输入哈希保持不变。独立技能包的 11 个文件与源目录一致，解压后生成的漂移结果与迁移后的归档重放结果完全相同。

使用 Maven Assembly 3.7.1 在临时工程中复用了 CLI 描述符的 `flydb-skills` 文件集合，实际 ZIP 包含迁移后的技能、说明和评测，并排除重复的 `.skill` 包与 Python 缓存。CLI 整包离线打包因本地缺少 `flydb-web:0.3.6` 依赖未完成；本次验证范围是技能文件集合与独立技能包。

## 仍未验证的范围

- 真实数据库元数据工具、目录权限、分页/截断和实时漂移；真实厂商执行与生产兼容性。
- 其他产品的内置规则、更多 DDL 形态和复杂动态程序；首批内置规则限于所引用的 OceanBase-Oracle 项目实测范围。
- 大型企业项目、跨宿主/模型、重复运行、长任务恢复；[10 条自动发现用例](trigger-cases.json) 尚未运行自动触发评测。
- CLI 原生 dry-run 诊断输出、安装渠道与自动更新；本版是独立 Skill 和分析附件，ROADMAP 正式条目继续按各自契约验收。
