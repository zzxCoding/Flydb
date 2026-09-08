# 本机 Web API 与执行记录 v1

本接口服务于 `flydb web`，不是远程管理平台或替代 MCP 的公开机器协议。
CLI JSON 和 Plan Artifact v1 继续保持原契约。所有请求仅允许服务打印的回环来源；
浏览器启动流程自动完成，不存在用户身份、角色或权限对象。

## HTTP

JSON 请求使用 `Content-Type: application/json`，正文最多 2 MiB。错误为
`{"code":"CONFIG_CONFLICT","detail":"..."}`；前端翻译稳定 code，原始诊断保留在详情。
POST 操作提交成功返回 HTTP 200 和 Run 快照，不代表数据库执行已经结束。

| 方法与路径 | 输入或用途 |
|---|---|
| `POST /api/session` | 页面以 `{}` 自动建立临时 HttpOnly Cookie，必须有与服务完全一致的 Origin；兼容 `{key}` 启动参数交换 |
| `POST /api/config/init` | `{workingDirectory, url, user?, driver?, databaseType?}`：返回 init 共享模板的 content/values/revision；纯内存，不创建目录或连接数据库 |
| `POST /api/config/document` | `{content, values?, validate?}`：纯内存 Properties 解析/补丁，返回 content/values/revision，不写文件或连接数据库 |
| `GET /api/profiles/:id/document` | 用户主动打开文件编辑时读取原文及修订，返回 content/values/revision；可能含原文件中的明文凭据 |
| `GET /api/bootstrap` | 版本、初始目录、状态目录、默认驱动目录、配置登记、最近 200 条记录、已知配置键 |
| `GET /api/files?path=…` | 有界本机目录浏览，返回 path/parent/entries |
| `GET /api/discover?path=…` | 有界发现配置文件，返回 files 和扫描限制信息 |
| `POST /api/profiles` | mode 为 import/create/discover/duplicate；返回登记数组 |
| `PUT /api/profiles/:id` | name/group/environment/workingDirectory，可选 driversDirectory |
| `DELETE /api/profiles/:id` | 只移除登记 |
| `POST /api/groups` | 分组组织操作：create/rename/delete/move/moveProfile，见下文；只修改本机登记 |
| `GET /api/profiles/:id/config` | revision/values/effective/sources/secretKeys，可含配置 error |
| `PUT /api/profiles/:id/config` | `{revision, values:{key:string或null}}` 或 `{revision, content}`；整文件保存强制校验，返回脱敏快照 |
| `POST /api/profiles/:id/actions` | 提交预定义操作，返回 Run |
| `GET /api/runs/:id` | 完整持久化快照，含结果和恢复状态 |
| `GET /api/runs/:id/events` | SSE，支持 Last-Event-ID 补发 |

配置登记必需 `workingDirectory`。import 使用 `configPath`；create 使用完整 `content`，兼容旧 `url/user`；
discover 使用 `paths` 数组；duplicate 使用 `sourceId/configPath`。显示元数据是可选的，
同一真实文件路径去重。配置内容保存到原文件，登记只保存路径和显示信息。

bootstrap 的 `groups` 为有序分组名称数组，包括空分组；旧登记的 profile.group 自动纳入列表。
`POST /groups` 输入 `action`：create 使用 `newName`；rename 使用 `name/newName`；delete 使用 `name`；
move 使用 `name/before`（空 before 表示末尾）；moveProfile 使用 `profileId/name`（空 name 表示未分组）。
新增名称去除首尾空白，最多 80 字符，不允许空白或重名。重名返回 GROUP_EXISTS，分组不存在返回 GROUP_NOT_FOUND。
删除分组将其配置移到未分组；重命名同步修改组内配置显示元数据。所有操作在 profiles.lock 下原子写入
profiles.json 的 profiles/groups，不修改原配置、SQL 或数据库。未分组是空字符串代表的内置归属，不可删除或重命名。
折叠状态按状态目录存于当前浏览器，搜索临时展开匹配分组，不覆盖折叠偏好。

文档转换的 `validate:false` 仅用于模式切换时保留待修正的字段，不绕过创建/保存时的校验。
新建草稿与 CLI 共用 `InitScaffolder.configurationDraft`；确认创建时完整原文交给同一个
`InitScaffolder.create`，使用 CREATE_NEW 生成 `flydb.conf`、`db/migration/V1__init.sql` 和缺失的
`drivers/README.md`。示例 SQL 为 `SELECT 1;`，不在创建时执行。已有配置/同名迁移返回
HTTP 409 / FLYDB-4004，已有驱动说明保留。默认迁移路径为绝对路径；GUI 改变项目目录时
只更新未自定义的默认路径，完整编辑的注释和自定义位置保持不变。
文件编辑专用接口是普通脱敏快照的显式例外：返回用户主动打开的原文件，不能用于报告或记录；
不解析环境变量/密码文件，不加载驱动或连接数据库。语法错误返回 CONFIG_SYNTAX，Unicode 转义
错误包含所在逻辑行的起始行号。整文件保存仍校验 revision、使用文件锁及原子替换。

操作正文公共字段为 `command`、`revision`、`requestId`（调用方生成的 UUID），可选临时 `password`。
command 只允许 inspect、validate、plan、migrate、undo-plan、undo、baseline、repair、clean。
baseline 增加 `baselineVersion` 和 `confirmed:true`；repair 增加 `confirmed:true`。
migrate/undo 必须使用对应 plan/undo-plan 结果中的 `planId`。

`clean` 先 POST `/api/profiles/<id>/clean-confirmation`，携带当前 `revision` 和可选临时 `password`。
该步骤只读取生效配置，不连接数据库或枚举对象；返回脱敏 `target`、`user`、`scope=CURRENT_SCHEMA`、
`token` 与 `expiresInSeconds=300`。界面必须说明将按方言清理当前 schema 的表、视图、序列及历史/锁表，
不限于 Flydb 创建的对象，且失败可能留下部分删除结果。用户勾选风险并输入 `CLEAN` 后，
通过 actions 提交 `command=clean`、`cleanToken`、`confirmed:true`、`confirmationText:"CLEAN"`。
后端校验确认引用与配置、目标绑定，引用单次有效，重启或五分钟后失效；旧迁移预览也会失效。
仅对本次调用设置 `flydb.clean-disabled=false`，不改写配置文件；底层仍复用 Core clean、锁与执行记录。
同一 requestId 重试返回原记录，不重复执行。失败或结果未知时必须先核对现场，不能自动重放。

planId 是本服务内存中的一次确认引用，30 分钟后或重启后失效，不是 Plan Artifact 的内容摘要。
同一请求标识会返回已有记录，不重放。最近 1,000 条持久化记录用于重启后的去重查找，
浏览器每次新意图使用新 UUID；过期请求不能当作永久幂等 API。
同一配置一次只运行一个操作，后台最多 4 个并行任务、24 个排队任务。
不同配置的数据库并发继续由 Core 锁保障。

配置文件修订不一致返回 `CONFIG_CONFLICT`；提交时变化返回 `PLAN_CHANGED`。
确认引用过期为 `PLAN_EXPIRED`；Core 锁内核对到目标/迁移清单变化为 `FLYDB-2011`。
其他常见 code 为 BUSY、INVALID_REQUEST、INVALID_PATH、FILE_EXISTS、PROFILE_NOT_FOUND、
SESSION_REQUIRED、REQUEST_FAILED。Web 没有任意 SQL、shell 或通用文件读取 API。

## Core 确认契约

`Flydb.prepareMigrate()/prepareUndo()` 返回 `PreparedMigrationPlan`；
`migrate(plan)/undo(plan)` 在数据库锁内重新解析当前待执行迁移，核对目标绑定和既有
Plan Artifact 摘要，再执行已经核对的解析对象。时间占位符沿用预览时间，避免确认过程
自身改变 `${flydb:timestamp}`。目标绑定包含实际 JDBC URL、数据库方言、schema、用户和历史表。
Core 不依赖 HTTP 或本地记录。

这保证迁移 SQL 集合的确认一致性，不是任意回调代码、外部文件或服务器状态的沙箱。
基础设施初始化沿用现有 Core 行为；在最终确认比较前可能创建历史表或锁对象。
SQL/Java 回调不在迁移预览清单内，且沿用原生命周期。

## Run 与事件

Web 使用 `GET /api/bootstrap?compact=true` 轮询：省略记录中的 SQL `statements` 数组，
并以 `detailsOmitted=true` 标记。不带参数的 bootstrap 保持完整响应。
打开预览或报告后通过 `GET /api/runs/<id>` 获取完整记录；详情未加载成功前不允许确认执行或导出不完整报告。
终态摘要按文件身份、大小及修改时间缓存，运行中记录继续检查文件锁。摘要不改变原始记录、计划 ID 或执行集合。

状态目录布局：`profiles.json`、`profiles.lock`、`runs/<时间戳-UUID>/`。
每次 Run 包含 `summary.json`、`events.jsonl`、`run.lock`。
`summary.json` 包含完整预览结果，读取时不采用配置登记文件的 8 MiB 限制；
采用文件流解析并保留 JSON 结构约束，不截断 SQL，也不修改旧记录。结果仍需在内存中解析，
该修复不表示无限大小的预览；配置登记文件继续保留 8 MiB 限制。
进程持有 OS 文件锁表示执行仍在运行，心跳超时不能推断它失败。

快照字段包括 schemaVersion=1、id、source=CLI/WEB、command、configPath、configRevision、
workingDirectory、target、startedAt、endedAt、lastActivityAt、sequence、status、verification、result。
Web 还记录 profileId/profileName/requestId；执行中按实际事件更新 progress/script/transactionResult/driver。

状态为 RUNNING、SUCCEEDED、FAILED、UNKNOWN；进程消失且没有终态时，读取返回 UNKNOWN 和
recovery=NO_TERMINAL_RESULT，不改写原证据。记录无法读取为 UNREADABLE_RECORD。
列表中的不可读记录仅保证包含 id、status=UNKNOWN 和 recovery=UNREADABLE_RECORD；
调用方不得假定命令、目标、时间或核验结果存在。Web 对缺失核验结果显示“未知”，
对无法计算的耗时显示“—”，不会将缺失值解释为成功、失败或未执行核验。
INTERRUPTED 是前端可识别的保留状态，当前无法确认数据库结果的进程中断采用 UNKNOWN。
verification 独立为 NOT_RUN、PASSED、FAILED；CLI 不隐式增加执行后数据库命令，因此默认为 NOT_RUN。

事件公共字段为 schemaVersion、runId、sequence（严格递增）、at、type。
事件类型包括 STARTED、DRIVER_RESOLVED、WAITING_FOR_LOCK、SCRIPT_STARTED、SQL_PROGRESS、
SQL_FAILURE、TRANSACTION_RESULT、SCRIPT_COMPLETED、VERIFYING、FINISHED。
脚本/进度事件携带 script/confirmed/total；失败还可携带 failureStart/failureEnd/lineNumber；
事务事件携带 phase/transaction。confirmed 是 JDBC 返回的确认数，不能推断事务提交。
事务 token：COMMITTED、ROLLED_BACK、NON_TRANSACTIONAL、COMMIT_UNKNOWN、ROLLBACK_FAILED、UNKNOWN。

SSE 使用 sequence 作为 id，正文 data 为事件 JSON。每秒发送注释心跳，最多 8 个同时观察者。
断线后从 Last-Event-ID 之后补发，结束发送 `event: finished`。前端同时每 5 秒读取快照，
即使关闭 SSE 也能恢复结果。现有事件不裁剪，因此可从 0 回放；损坏的尾行不是终态证据。

执行日志先写事件再更新摘要。记录错误不改变 Core 已有事务结果；无法完整记录时标注
recording=INCOMPLETE。磁盘无法写入时不能承诺留存证据，CLI 会在 stderr 提示，stdout 契约不变。

记录会去除运行时已知密码、敏感配置值和 URL 秘密参数。不要把业务敏感常量直接写入可分享 SQL；
未标识的业务数据无法由名称规则完整识别。字段和保存纪律见[GUI 指南](../getting-started/web.md)。
