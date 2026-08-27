# Flydb 路线图

本路线图描述 Flydb 的演进方向、各阶段边界与当前进度。它代表当前规划方向，不构成交付时间或功能承诺；阶段与版本号并非一一对应，详细任务以仓库 Issue 为准。

## 阶段一：可靠的迁移运行时

让迁移引擎本身值得信任。这是后续一切能力的基础——模型再强，也无法替代事务、锁、版本排序、迁移历史与故障恢复的确定性。

- [x] 版本化 `V`、可重复 `R`、撤销 `U` 迁移；递归发现与严格命名校验，不静默跳过
- [x] `init` / `migrate` / `info` / `validate` / `baseline` / `repair` / `undo` / `clean` / `version` CLI 命令
- [x] 按方言选择并发锁（advisory lock、锁表、DBMS_LOCK）、DDL 事务差异、失败记录阻断与 repair 恢复
- [x] OceanBase-Oracle 真实迁移加固：锁表行锁、纯 DML 单脚本事务、按目标 schema 清理序列，以及 batch 失败的可信定位边界
- [x] checksum 校验与修改检测、migrate/undo 的 `--dry-run`
- [x] 占位符、回调、UTF-8 Properties、环境变量、密码文件等配置体系；稳定退出码与错误码
- [x] MySQL、PostgreSQL、Oracle、达梦 DM8、KingbaseES、openGauss、OceanBase、TiDB 内置方言与 `DatabaseType` SPI
- [x] Spring Boot 2 / 3 starter 与可运行示例
- [x] `flydb-cli` Agent Skill 与评测用例
- [x] `v0.2.0` GitHub Release 与 CLI 发行包
- [x] Maven Central 发布渠道与兼容性矩阵公开化（brew 等包管理器渠道见阶段二）

## 阶段二：开发体验与机器契约

让任何程序（CI、IDE、外部宿主、Agent）都能稳定调用 Flydb。投资集中在机器契约上——它是阶段三 MCP 适配与阶段五协议的直接前置。

- [x] `--json` 机器可读输出：稳定 schema、stdout/stderr 分离
- [x] 命令、配置、错误码契约的版本化承诺（protocolVersion），与 `--json` 同批交付
- [x] CI 接入文档：GitHub Actions 与 Jenkins 的官方示例与片段
- [x] `flydb-skills` 成为合法的 Agent Plugins 1.0.0 插件包（`plugin.json`；`mcp.json` 随阶段三交付）

### 按需启动

以下便利项不设排期，出现真实用户需求后再启动：

- [ ] brew / SDKMAN / scoop 安装渠道（前置：发行包增加 tar.gz 格式）
- [ ] 官方 Docker 镜像（信创环境多为内网，外部镜像可达性有限）

## 阶段三：Agent 分发（当前阶段）

让主流 Agent 都能安全调用 Flydb；Flydb 不绑定任何模型或平台，适配层保持薄。

- [x] 基于稳定 CLI 契约的 MCP 适配（TypeScript Adapter，只映射 Flydb 领域命令，不提供通用 `execute_sql`；写入工具默认不注册、`FLYDB_MCP_ENABLE_WRITES=true` 显式开启，永不暴露 `clean`）
- [x] Plan Artifact v1：迁移计划的结构化表示，供人、CI 与 Agent 消费同一份计划（dry-run 信封携带 `plan.id` 确定性摘要）
- [ ] npm 包 `flydb-mcp` 正式发布与 Agent Plugin 分发验证（随下一个 Release 交付，名称已在 registry 预检）

## 近期版本：0.3.4 长迁移执行遥测

`0.3.4` 是在 `v0.3.3` OceanBase 实库加固基础上的非破坏性小版本，聚焦长迁移期间的可观测性与失败定位；不改变迁移排序、事务、锁、历史记录或自动恢复语义。

- [ ] 语句级周期进度：向诊断通道报告当前脚本、已确认完成数、总语句数、耗时与速率；保持 `--json` stdout 单行契约不变
- [ ] 失败执行快照：区分事务模式、已确认成功、已回滚和驱动无法可靠定位的批次范围，不把推算值或未知数据库状态描述成已提交事实
- [ ] 补齐 OceanBase-Oracle 实测限制：组合 `MODIFY` 应拆分类型与可空性变更；登录用户与 `CURRENT_SCHEMA` 不同时说明 `USER_*` 字典视图风险及 `ALL_* + owner` 写法

### 0.3.4 非目标

- 不修改历史表 schema，也不让 `info` 持久化展示语句级失败详情
- 不新增自动 `undo --partial`、跨进程 `flydb progress` 或单脚本 DDL 并行执行
- 不在该版本引入通用 SQL 兼容性引擎、`CleanReport` 或全对象“空 schema”断言

## 阶段四：存量变更智能（0.4.0 起）

回答“改这个字段，究竟会伤到什么”。针对运行多年、依赖散落在数据库对象与应用代码里的存量系统。

### 0.4.0：方言兼容性预检

`0.4.0` 启动阶段四，但不一次交付整个存量分析体系。首个纵向切片是在现有 dry-run 计划上增加可解释、可机器消费的方言诊断，让已知兼容性风险在执行前暴露。

- [ ] 对 dry-run 已解析语句输出诊断码、严重级别、脚本/行号、适用数据库家族与版本证据；保持 `flydb-plan-v1` 的确定性摘要语义不变
- [ ] 首批覆盖 OceanBase 4.2.1.x Oracle 模式实证规则：组合 `MODIFY`、scale 变更，以及登录用户与 `CURRENT_SCHEMA` 不同时使用 `USER_*` 字典视图的风险
- [ ] 诊断默认只告警，不自动改写 SQL，也不把启发式判断包装成必然失败；数据库状态相关结论明确标注是否完成实时核验

### 后续 0.4.x

- [ ] Schema 快照与漂移（drift）检测
- [ ] 数据库对象依赖分析：View / Procedure / Trigger / Index
- [ ] 应用引用扫描：MyBatis Mapper、JPA Entity、JDBC、SQL 文件
- [ ] 影响报告与覆盖率：明确标注未知项，而不是假装依赖不存在

## 阶段五：Agent 安全变更运行时

把数据库变更变成确定性协议：Plan → Validate → Risk → Approval → Apply → Verify → Record。

- [ ] 风险评级与审批边界
- [ ] 验证证据（Verification Artifact）：在“SQL 执行成功”之外，核对 schema 状态、约束与迁移历史
- [ ] `CleanReport` 与清理后验证：只断言 Flydb 明确支持清理的对象类型及记账表，不把存储过程、类型等范围外对象误报为 clean 失败
- [ ] 失败恢复证据：把执行快照升级为可持久化、可机器消费的 Recovery Artifact，保留“已确认/已回滚/未知”边界
- [ ] 回滚分类：按变更类型区分可逆、有条件可逆与仅前向修复

## 不做的事

以下方向明确不做，以保持专注；宿主已经擅长的事情交给宿主：

- 数据库 GUI / IDE
- ORM 或应用开发框架
- 通用数据库 MCP（`execute_sql` / `list_tables` 已高度通用化）
- 完整企业治理平台（RBAC、SSO、审批中心）
- 根据非事务 DDL 的半执行状态自动推导并执行逆 SQL；恢复必须来自显式 `U` 迁移、经评审的前向修复或人工处置计划
- 绑定特定 AI 模型或厂商
