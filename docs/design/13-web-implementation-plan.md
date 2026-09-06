# 13 Web 工作台实施与验收记录

日期：2026-09-06。分支：`codex/flydb-web-workbench`，基线 `623a947`。
状态：实现与用户验收完成，进入 **0.3.5 发布流程**。下方分阶段记录保留当时的版本和验收边界。
产品范围与模块决策见 [12 Web 工作台](12-web-workbench.md)。

## 1. 已交付内容

- [x] 官网深蓝黑、亮蓝/青色、暖白及三瓣标记；没有账户、登录、角色、团队或审批页面。
- [x] `flydb web`、原配置导入、目录发现、新建、复制、分组、环境标签和移出。
- [x] 连接表单、高级配置、密码引用、临时密码、生效来源与已有驱动目录选择。
- [x] 原文件保存、类型预检、外部编辑检测、字段冲突合并和草稿保护。
- [x] 共享 Runtime、Core 计划绑定与执行事件、持久记录、本机 HTTP 和 SSE。
- [x] 检查、校验、SQL 预览、确认执行、后验、报告、baseline/undo/repair。
- [x] CLI 独立记录、运行中观察、异常进程终态待核验，不自动重放。
- [x] 中英文、明暗主题、响应式布局、键盘焦点、离线资源和许可证。
- [x] 清洁构建、Java 8/17、原模块回归、真实 MySQL 候选包验收及页面截图。

## 2. 要求与证据

| 要求 | 实现 | 验证 |
|---|---|---|
| G01–G03 启动与配置 | WebCommand、ProfileStore、LocalFiles、ProfileDialog | ZIP 启动；GUI 导入、复制、分组；移出保留原文件 |
| G04–G06 编辑、来源和冲突 | ConfigurationDocument、ConfigurationService、ConnectionEditor | CRLF/注释/引用/重复键；HTTP 冲突；非法值保存前拒绝；真实草稿合并与原文件读回 |
| G07–G09 检查与确认 | OperationService、PreparedExecution、PlanPanel | validate/plan/migrate；SQL 变化触发 FLYDB-2011；不同目标单测；重复请求返回同 Run |
| G10–G13 进度与恢复 | ExecutionObserver、RunStore、CliRunRecorder、RunPanel | 长 SQL、SSE 补发、CLI 运行中观察、SIGKILL 后 UNKNOWN；执行/后验分离 |
| G14–G15 高级处置与驱动 | Core 原命令、独立确认、驱动解析事件 | 临时库 baseline/undo/repair；实际驱动类和来源；缺驱动失败终态 |
| G16 双语 | vue-i18n、报告摘要与本地化说明 | 键集合/消息编译、英文复数、切换保留草稿；技术原文保留 |
| G17 视觉与可访问性 | 官网 token、响应式 CSS、Naive UI 对话框 | 桌面/窄屏、明暗主题、Tab 焦点和移动菜单；见第 4 节 |
| G18 构建与兼容 | Maven 内嵌前端、Node 22 构建、Java 8 模块 | 清洁 reactor、字节码、MCP、ZIP 资源和许可证；平台边界见第 5 节 |

## 3. 自动化结果与复现

完整 Java 17 清洁构建：

```bash
./mvnw -B clean verify -DskipITs
```

Core **356**、Runtime **35**、Web **5**、CLI **13**、Boot 2 **6**、Boot 3 **6**，
共 **421 个测试通过**。数据库集成矩阵的 9 个条件测试跳过，不计为实库通过。
Core 零运行时依赖门禁与 JaCoCo 80% 行覆盖率门禁通过。
清洁构建确认 CLI JAR 没有旧支持类，Web JAR 只含当前 JS/CSS 哈希资源。

JDK 8 下也通过了 CLI 依赖 reactor，以及包含 Boot 2 的兼容构建：

```bash
./mvnw -B verify -pl flydb-core,flydb-cli,flydb-spring-boot-2-starter,examples/boot2-demo -am
./scripts/check-bytecode.sh 52 \
  flydb-core/target/classes flydb-runtime/target/classes flydb-web/target/classes \
  flydb-cli/target/classes flydb-spring-boot-2-starter/target/classes examples/boot2-demo/target/classes
```

前端类型检查、生产构建和 **4 个测试**通过；MCP **58 个单元测试**与 **7 个真实 CLI
只读端到端测试**通过。MCP 的独立 PostgreSQL 写入矩阵未运行。

实库验收只使用用户明确授权的临时容器 `flydb-web-qa-20260906`：
MySQL 8.4.4、回环端口 51578、无用户 bind mount。驱动来自已有本地测试依赖，
只放入临时解压目录，不进入仓库或交付 ZIP。复现入口：

```bash
python3 scripts/verify-web-candidate.py \
  --cli /path/to/extracted/bin/flydb \
  --container flydb-web-qa-20260906 \
  --allow-temporary-writes
```

[验收脚本](../../scripts/verify-web-candidate.py)只接受带
`com.flydb.purpose=gui-acceptance` 标签、无 bind mount、只回环暴露的已有容器。
它会写入其中的 `flydb_gui` 临时数据库，运行前需取得写入授权；
不会创建/删除容器或下载驱动。每次使用独立历史表、临时目录，输出 `evidence.json`。

本次 **18 项检查通过**：CLI 不依赖 Web 的记录、Web 读回、内嵌资源、CLI 长任务观察、
进程强制终止后 UNKNOWN、预览前校验、重复提交、活动快照恢复、迁移及后验、
实际驱动来源、SSE 补发、撤销、旧 SQL 计划阻断、基线、SQL 失败诊断、显式修复、
配置冲突、移出保留文件。另通过真实 GUI 完成迁移、撤销和再次迁移，
最后 V2 的 3 条 SQL 执行约 12 秒并通过后验。

## 4. 视觉与交互验收

截图来自真实候选 ZIP 与临时库：

- [中文深色工作台](web/migrations-dark.jpg)
- [英文浅色连接设置与键盘焦点](web/connection-light-en.jpg)
- [中文 SQL 预览](web/plan-dark-zh.jpg)
- [真实执行中](web/run-active-zh.jpg)与[执行结果](web/run-result-zh.jpg)
- [390px 英文连接页](web/connection-mobile-en.jpg)
- [视口测量](web/viewport-checks.json)

1366、1024、768、720、390px 的 scrollWidth 均未超出视口；桌面另检查 1440px。
已验证清空/重填主机、切换语言、查看全部记录而保留草稿、合并外部编辑并读回原文件、
恢复连接、复制/分组、移动菜单切换配置及 Tab/PageUp 键盘操作。
基础普通文本配色达到 4.5:1，按钮 hover 色已加深；减少动态效果的样式关闭动画和过渡。

720×450px 用于模拟 1440×900px 在 200% 放大时的重排，
**不等同于浏览器原生 200% 缩放实测**；几何/截图检查也不替代完整屏幕阅读器审计。

## 5. 验证边界

- 本机为 macOS。Windows 启动脚本和 CI 配置已保留，未在 Windows 实机执行。
- 本次 GUI 实库证据仅 MySQL；没有新增 PostgreSQL、Oracle、达梦、金仓等厂商实库结论。
- 未推送分支、触发远程 CI、发布 Release、部署 Maven Central 或 npm。
- SQL/Java 回调不在迁移 SQL 预览清单内，沿用原生命周期；见 [API 参考](../reference/web-api.md)。
- 列表显示最近 200 条记录，旧文件不自动清理；未标识的业务敏感常量需按项目要求检查后分享。

## 6. 用户验收与发布门

使用说明：[中文](../getting-started/web.md) / [English](../getting-started/web.en.md)。
建议按“导入/新建 → 配置与来源 → 状态检查 → 预览 → 执行结果 → 切换配置/语言”验收，
优先确认易用性和官网风格，再检查高级操作与异常诊断。

候选包仍保留源码版本号 0.3.4，只是本地未发布构建；公开 0.3.4 ZIP 没有 GUI。
用户明确验收通过后，再确定下一版号和发布流程。自动化通过不代替用户验收。

## 7. 用户验收反馈修复：Oracle 与 Safari（2026-09-06）

- 两处数据库下拉共用的 JdbcEditor 漏了 Oracle。已补充 Oracle、1521 默认端口、
  服务名/SID 选择及已有简单 Oracle URL 回填；复杂描述符和带凭据地址保持直接编辑。
- Safari 打开普通地址没有启动 fragment 或已有 Cookie，原实现返回 SESSION_REQUIRED，
  被误报为本机服务不可用。改为页面通过严格同源 JSON POST 自动建立连接；
  没有 Origin、跨源及 null Origin 仍被拒绝，API/SSE 继续校验临时 Cookie。
  新浏览器和同端口重启后的“重试”均不再依赖复制特殊链接。
- 回归先复现：Oracle 地址解析返回 undefined；无凭证同源启动期望 200、实际 401。
  修复后 Java 8 的 Core/Runtime/Web/CLI 共 **410** 个测试和前端 **8** 个测试通过；
  Java 17 定向 Web 的 **6** 个测试也通过。
- 使用修复后的 ZIP 实测 Safari 普通地址 `http://127.0.0.1:8317/`，成功显示原配置、
  版本 2 及两条已执行迁移；内置浏览器实测新建和连接设置两个 Oracle 下拉、服务名/SID URL。
  测试表单已退出且未保存到原配置。本轮未连接 Oracle 实库或执行数据库写入。
- 新候选包替换原本地交付文件，仍未发布。第 3 节的 MySQL 18 项实库证据属于首轮验收；
  本轮仅针对上述界面与浏览器连接反馈复核，不将其表述为新增 Oracle 实库验证。

## 8. 用户验收反馈精修：完整配置文件编辑（2026-09-06）

- 新建与已有配置统一为表单/文件两种编辑方式；共用字段组件、草稿状态和 Java Properties
  转换模型。完整文件覆盖注释、顺序、续行和额外键，保存仍执行修订检查和原子替换。
- CodeMirror 编辑器离线打包并按需加载，提供行号、语法高亮、键补全、查找替换、撤销、
  自动换行及快捷保存。新增对话框加宽，标题/操作栏固定；文件模式收起位置与名称。
- 明确区分配置格式检查和数据库连接：检查/保存/创建均不会执行迁移。格式错误保留输入，
  Unicode 转义错误显示逻辑行起点；取消、还原、外部冲突对照保护草稿。
- 原文件接口仅在主动文件编辑时使用，是普通脱敏快照的显式例外。页面不持久保存草稿，
  原文不进入执行记录、报告；不展开环境变量或密码文件。
- Java 17 与 Java 8 相关 reactor 均通过：Core 356、Runtime 36、Web 9、CLI 13，
  共 **414** 个测试；前端类型检查、构建和 **11** 个测试通过。
- 实际 GUI 在临时 `editor-qa` 目录完成：Oracle 表单→文件→表单修改→文件、注释和
  自定义键保留、完整新建落盘、非法 Unicode 保存阻断、外部改动对照与合并、⌘+S 保存、
  查找替换面板、中英文和明暗主题。读回文件确认无效保存未改动原文件，合并内容完整保留。
- 本轮只编辑临时文件，没有数据库写入或新增 Oracle 实库验证；仍未提交、推送或发布。

- 收尾实测：保存/检查提示随语言即时切换；独立服务断开时编辑器与草稿保留，重启后
  点击重试并保存成功。390px 窄屏没有横向溢出。
- 最终界面截图：[中文深色文件编辑](web/file-editor-dark-zh.jpg) /
  [英文浅色新建配置](web/file-editor-light-en.jpg)。

## 9. GUI 新建统一到 init（2026-09-06）

- GUI 从共享 InitScaffolder 获取完整配置草稿，用户修改后仍由同一个初始化入口创建文件；
  不再由 Web 单独维护简版模板或落盘逻辑，不启动 CLI 子进程。
- 新建生成 flydb.conf、db/migration/V1__init.sql 和缺失的 drivers/README.md。
  中英文界面明确列出文件，并说明 SELECT 1 示例须在迁移前替换；创建不执行 SQL。
- 项目目录更新时，只调整仍采用默认值的绝对迁移路径；手工修改的路径、注释和额外键保留。
- 回归先复现模板接口 404、已有迁移未阻断创建；修复后 Java 8 相关 reactor 共 **416**
  个测试通过，前端 **14** 个测试与类型检查/构建通过。
- 从新 ZIP 启动 GUI，在独立临时目录创建项目，确认共享模板、目录修改后路径、追加注释
  均完整落盘；三份文件存在且示例仅含 SELECT 1。API 回归对比 CLI 与 Web 的初始化产物，
  验证冲突不覆盖配置/迁移文件、不登记失败项目。
- 本轮没有数据库操作；仍未提交、推送或发布。

## 10. 已有项目操作后页面无法滚动（2026-09-06）

- 实际浏览器复现：打开执行详情并关闭，所有弹窗消失后 html 仍为 overflow:hidden，
  页面高度大于视口但滚轮无法改变 scrollTop。嵌套的“全部执行记录→执行详情”同样复现。
- 根因是执行详情、迁移预览和高级操作的 NModal 默认插槽根节点带有 v-if；关闭时节点
  提前卸载，Naive UI 的 after-leave 无法完成并释放文档滚动锁。
- 保留弹窗根节点直到离场结束，只条件渲染内部内容；不手工重置全局 overflow，也不关闭
  正常的弹窗滚动锁，确保嵌套弹窗仍正确阻止背景滚动。
- 增加使用真实 App / NModal 的 DOM 回归：反复打开关闭详情、嵌套历史、取消迁移预览、
  取消高级操作，四项在修复前失败、修复后通过；原有 overflowY 样式恢复也被断言。
  前端共 18 项测试及类型检查通过。happy-dom 仅作为测试依赖，不进入发行运行时。
- 本轮不执行数据库操作，沿用既有本地候选版本号，未提交、推送或发布。

- 最终 ZIP 的真实浏览器复核：关闭内层详情后保留外层记录列表的滚动锁；全部关闭后
  html overflow 恢复为空，实际滚轮使主页面 scrollTop 从 0 变为 837（修复前保持 0）。

## 11. 0.3.5 发布前验证（2026-09-06）

- 用户确认 GUI 验收完成并授权发布；产品、前端及随包插件统一为 0.3.5。
- Java 17 全 reactor verify 通过：428 项 Java 测试通过，9 项数据库条件测试跳过。
  本轮显式设置 `-Dflydb.integration.enabled=false`，并移除厂商测试 URL 环境变量，
  不新增本地数据库写入。Core 覆盖率与零运行时依赖门禁通过。
- Java 8 相关 reactor verify、Java 8/17 字节码门禁通过；前端类型检查、生产构建及
  18 项测试通过。临时本地 Maven 仓库 deploy 演练和发行产物门禁通过。
- MCP 58 项单元测试、7 项实际 0.3.5 ZIP 的 CLI 端到端测试通过；独立写入矩阵
  的 5 项测试跳过。`qs` 更新为 6.16.0 后，MCP 和前端依赖审计均无已知漏洞。
- 实际 ZIP 完整性、Java 8 下版本输出、独立 GUI 启动、普通地址会话、网页资源、
  CodeMirror 离线分块、第三方许可证和随包 Skill 版本通过检查；未连接数据库。
- Skill 格式、文档链接、插件版本和 12 个评测用例的结构通过校验；本轮未执行
  LLM 行为评测。新增 Web 启动、旧版升级和外部配置冲突三个用例。
- 本节为本地发布前证据；远端 CI、GitHub Release、下载校验和 Maven Central
  上线状态需在实际发布后分别核验。
