# 错误码参考

错误码是 Flydb 的稳定契约。CLI 会将异常映射为退出码，自动化系统可按错误码分类处理；消息中的脚本名、语句序号和行号用于定位问题。`--json` 模式下错误码进入 `error.code` 字段，schema 见 [JSON 输出参考](json-output.md)。

## 错误码

| 错误码 | 含义 | 常见原因 | 建议动作 |
|---|---|---|---|
| `FLYDB-1001` | 连接失败 | URL、账号、密码、网络或数据库进程异常 | 检查连接参数和数据库状态 |
| `FLYDB-1002` | 无法识别数据库类型 | URL 前缀或产品名不在支持矩阵，或探测有歧义 | 显式设置 `--database-type` |
| `FLYDB-1003` | JDBC 驱动未找到 | 本地来源没有驱动、坐标/类名错误，或 Maven 私服认证/网络失败 | 按消息中的解析轨迹修正 settings/坐标，或将 JAR 放入提示的 `drivers/` |
| `FLYDB-2001` | 非法版本号 | 版本未以数字开头、含空段/非法字符，或 `V`/`U` SQL 候选命名无法解析 | 使用 `V<版本>__<描述>.sql`；版本 token 可用点、下划线或连字符分隔 |
| `FLYDB-2002` | 重复版本 | 多个脚本解析为同一版本 | 分配唯一版本号 |
| `FLYDB-2003` | 迁移校验不一致 | checksum 不一致、已应用脚本在当前迁移集合中 `MISSING`，或数据库历史版本相对本地为 `FUTURE` | 按详情分类处理；checksum 预期改动才执行 `repair`，`MISSING`/`FUTURE` 先检查 locations、CWD、路径过滤和代码版本 |
| `FLYDB-2004` | 存在失败记录需 repair | 上次迁移留下 `success=false` | 修正脚本后先执行 `repair` |
| `FLYDB-2005` | 旧式 R 前缀命名 | 发现 `R<version>__...sql` | 回退脚本改为 `U<version>__...sql`，可重复脚本改为 `R__...sql` |
| `FLYDB-2006` | 乱序迁移 | 未启用 `out-of-order` 时补执行低版本 | 按序补齐，或明确设置 `out-of-order=true` |
| `FLYDB-2007` | baseline 前置不满足 | 已有迁移记录或 baseline 冲突 | 检查历史表与 baseline 版本 |
| `FLYDB-2008` | 缺少 undo 脚本 | 最近版本没有对应 `U<version>__...sql` | 补齐撤销脚本 |
| `FLYDB-2009` | 未定义占位符 | SQL 引用了未配置的迁移占位符，或业务运行时模板被误识别为迁移占位符 | 前者补 `flydb.placeholders.*`；后者设置 `flydb.placeholder-replacement=false` 原样保留 |
| `FLYDB-2010` | 迁移执行失败 | 某条 SQL 被数据库拒绝，或执行期间 JDBC 连接中断 | 先读 stderr/应用日志中的“迁移失败执行快照”：核对失败阶段、事务模式、JDBC 已确认执行数、定位可信度与事务结果；`confirmed` 只表示首个已定位失败项之前的连续成功前缀，不包含失败后的返回项，也不等于已提交。纯 DML 单脚本事务只有明确显示“已回滚”时才可在连接恢复后重跑；非事务、回滚失败或提交结果未知时先核对数据库现状。`batch-size>1` 仅在 JDBC 返回 `EXECUTE_FAILED` 时精确定位，遇错即停计数是推算值，无可靠标记时按候选批次排查；不要自动 repair 或重放 |
| `FLYDB-3001` | 获取迁移锁超时 | 其他进程正在迁移或锁等待过短 | 确认并发任务，必要时调大锁超时 |
| `FLYDB-4001` | 未知配置键 | 拼写错误或使用了未支持的键 | 删除或修正配置键 |
| `FLYDB-4002` | 缺少必填配置项 | CLI 没有 URL，或 Spring Boot 没有 DataSource/`flydb.url` | 提供 JDBC URL 或应用 DataSource |
| `FLYDB-4003` | clean 被禁用 | `clean-disabled=true` | 明确设置 false 并完成二次确认 |
| `FLYDB-4004` | init 目标文件已存在 | `flydb.conf` 或首个迁移脚本已存在，Flydb 拒绝覆盖 | 选择空目录，或备份并移走冲突文件后重试 |
| `FLYDB-4005` | 迁移位置不存在 | `flydb.locations` 指向的文件系统目录或 classpath 路径不存在；`filesystem:` 相对路径按执行 CLI 的当前工作目录解析 | 核对前缀（`classpath:`/`filesystem:`）与路径；相对路径需在正确的工作目录执行或改用绝对路径 |

典型消息格式：

```text
[FLYDB-3001] 获取迁移锁超时（Lock acquisition timed out）
可能原因: 另一个 flydb 进程正在对该数据库执行迁移。
建议操作: 确认无并发迁移后重试，或调大 flydb.lock-timeout-seconds。
```

## CLI 退出码

| 退出码 | 含义 |
|---:|---|
| `0` | 成功 |
| `1` | 一般错误（连接、SQL 执行等） |
| `2` | 校验失败 |
| `3` | 锁冲突或锁超时 |
| `4` | 配置错误 |
| `5` | 用户中断（SIGINT；`--json` 下不保证输出信封） |
