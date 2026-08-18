# Flydb 路线图

本路线图描述 Flydb 的演进方向、各阶段边界与当前进度。它代表当前规划方向，不构成交付时间或功能承诺；阶段与版本号并非一一对应，详细任务以仓库 Issue 为准。

## 阶段一：可靠的迁移运行时

让迁移引擎本身值得信任。这是后续一切能力的基础——模型再强，也无法替代事务、锁、版本排序、迁移历史与故障恢复的确定性。

- [x] 版本化 `V`、可重复 `R`、撤销 `U` 迁移；递归发现与严格命名校验，不静默跳过
- [x] `init` / `migrate` / `info` / `validate` / `baseline` / `repair` / `undo` / `clean` / `version` CLI 命令
- [x] 按方言选择并发锁（advisory lock、锁表、DBMS_LOCK）、DDL 事务差异、失败记录阻断与 repair 恢复
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

## 阶段四：存量变更智能

回答“改这个字段，究竟会伤到什么”。针对运行多年、依赖散落在数据库对象与应用代码里的存量系统。

- [ ] Schema 快照与漂移（drift）检测
- [ ] 数据库对象依赖分析：View / Procedure / Trigger / Index
- [ ] 应用引用扫描：MyBatis Mapper、JPA Entity、JDBC、SQL 文件
- [ ] 影响报告与覆盖率：明确标注未知项，而不是假装依赖不存在

## 阶段五：Agent 安全变更运行时

把数据库变更变成确定性协议：Plan → Validate → Risk → Approval → Apply → Verify → Record。

- [ ] 风险评级与审批边界
- [ ] 验证证据（Verification Artifact）：在“SQL 执行成功”之外，核对 schema 状态、约束与迁移历史
- [ ] 回滚分类：按变更类型区分可逆、有条件可逆与仅前向修复

## 不做的事

以下方向明确不做，以保持专注；宿主已经擅长的事情交给宿主：

- 数据库 GUI / IDE
- ORM 或应用开发框架
- 通用数据库 MCP（`execute_sql` / `list_tables` 已高度通用化）
- 完整企业治理平台（RBAC、SSO、审批中心）
- 绑定特定 AI 模型或厂商
