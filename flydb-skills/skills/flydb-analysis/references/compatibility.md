# 方言兼容性预检

对 SQL 文件或现成 dry-run 计划生成独立诊断附件。首版内置下面三条 OceanBase-Oracle 已知风险；其他产品可以基于实际可取得的一手资料调查，但要逐项写明来源，不能套用这三条规则。

## 执行

1. 记录产品、兼容模式、版本、登录用户与目标/current schema 的已知值。SQL 文件保留原始路径、起始行和全文；已有 Flydb 计划时读取成功信封中的 migrations[].statements[].sql/lineNumber，并原样引用 plan.algorithm/id。计划中的 targetVersion 是迁移版本，不是数据库版本。
2. 以语句或 PL/SQL 块为单位读上下文，区分可执行 SQL、注释、字符串和动态构造。不要按分号直接切断过程。只需要更多上下文的条目继续追查；源码中未展开的占位符或脱敏 SQL 进入 unknowns。
3. 按下表逐条核对语义与前提。为每条规则记录 match / not-matched / unknown / not-applicable 和实际检查范围。缺少旧列定义时不能断定 scale-only；有 USER_* 名称时先核对目标身份与会话切换，而非见字就报错。
4. 按 [报告约定](report.md) 输出 task=compatibility。诊断含 code、severity、ruleSource、applicability、targetDatabase、statementLocation、evidenceIds、message、nextCheck。默认 severity=warning；它不表示 SQL 必然失败。无命中只表示所列规则未发现已知风险。

完成条件：每条内置规则都有检查状态；命中有 SQL 定位和版本依据；未取得的状态前提明确，不执行待评估语句。

## 首批规则

共同证据：[Flydb OceanBase-Oracle 已知限制，固定源码修订](https://github.com/zzxCoding/Flydb/blob/56bc3baef4a4e5c9eefe440644a4343aa960d0a4/docs/getting-started/oceanbase-oracle.md#已知限制)。这是项目实测记录，不是厂商全版本承诺。文档记录 4.2.x 差异及 4.2.1.2 环境；优先覆盖路线图的 4.2.1.x Oracle 模式。其他补丁版本仍需目标环境核验。

| code | 何时命中 | 缺少哪些信息时保留 unknown | 建议 |
|---|---|---|---|
| FDA-OB-001 | 同一 ALTER TABLE MODIFY 子句同时修改类型/长度和 NULL/NOT NULL 属性 | 无法确认是否为可执行 DDL，或动态块尚未解析 | 评估拆成类型变更与可空性变更两条；本次仅建议，拆分后的业务数据条件仍需验证 |
| FDA-OB-002 | 数值类型旧定义与新定义可比，精度不变而 scale 改变 | 缺少旧 precision/scale，或目标类型本身不明确 | 在对应产品版本评估受支持的迁移方案，补充无害验证；不自动生成替代迁移 |
| FDA-OB-003 | 对目标 schema 的目录检查使用 USER_*，且登录用户与该 current schema 不同 | 账号/schema/执行上下文未知，或查的是登录用户自身对象 | 核对对应 ALL_* 视图及显式 owner 条件，并验证目录可见权限 |

applicability 使用 applicable / conditional / unknown / not-applicable：目标明确为 OceanBase 4.2.1.x Oracle 模式且前提有证据时 applicable；其他 4.2.x 版本参考该记录时 conditional；版本/模式缺失时 unknown；明确为其他产品或 MySQL 模式时内置规则 not-applicable。条件未知可发“待核验”诊断，basis=inferred，不伪造匹配成立。

保留每条规则的来源范围和本次是否实时核验。静态定义、用户陈述和现场元数据分别标识；同一会话里的 ALTER SESSION 也会改变后续语句适用条件，需要按顺序跟踪。
