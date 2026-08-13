# 错误码参考

错误码是 Flydb 的稳定契约。CLI 会将异常映射为退出码，自动化系统可按错误码分类处理；消息中的脚本名、语句序号和行号用于定位问题。

## 错误码

| 错误码 | 含义 | 常见原因 | 建议动作 |
|---|---|---|---|
| `FLYDB-1001` | 连接失败 | URL、账号、密码、网络或数据库进程异常 | 检查连接参数和数据库状态 |
| `FLYDB-1002` | 无法识别数据库类型 | URL 前缀或产品名不在支持矩阵，或探测有歧义 | 显式设置 `--database-type` |
| `FLYDB-1003` | JDBC 驱动未找到 | `drivers/` 缺少驱动或类名错误 | 放入对应驱动 JAR，或设置 `--driver` |
| `FLYDB-2001` | 非法版本号 | 版本不是一个或多个非负整数段 | 重命名为 `V1__description.sql` |
| `FLYDB-2002` | 重复版本 | 多个脚本解析为同一版本 | 分配唯一版本号 |
| `FLYDB-2003` | 校验和不匹配 | 已应用脚本被修改 | 预期改动执行 `repair`，否则还原脚本 |
| `FLYDB-2004` | 存在失败记录需 repair | 上次迁移留下 `success=false` | 修正脚本后先执行 `repair` |
| `FLYDB-2005` | 旧式 R 前缀命名 | 发现 `R<version>__...sql` | 回退脚本改为 `U<version>__...sql`，可重复脚本改为 `R__...sql` |
| `FLYDB-2006` | 乱序迁移 | 未启用 `out-of-order` 时补执行低版本 | 按序补齐，或明确设置 `out-of-order=true` |
| `FLYDB-2007` | baseline 前置不满足 | 已有迁移记录或 baseline 冲突 | 检查历史表与 baseline 版本 |
| `FLYDB-2008` | 缺少 undo 脚本 | 最近版本没有对应 `U<version>__...sql` | 补齐撤销脚本 |
| `FLYDB-2009` | 未定义占位符 | SQL 引用了未配置的占位符 | 补 `flydb.placeholders.*`，检查错误行号 |
| `FLYDB-2010` | 迁移执行失败 | 某条 SQL 被数据库拒绝 | 按脚本、语句序号和行号修正后重试 |
| `FLYDB-3001` | 获取迁移锁超时 | 其他进程正在迁移或锁等待过短 | 确认并发任务，必要时调大锁超时 |
| `FLYDB-4001` | 未知配置键 | 拼写错误或使用了未支持的键 | 删除或修正配置键 |
| `FLYDB-4002` | 缺少必填配置项 | CLI 没有 URL，或 Spring Boot 没有 DataSource/`flydb.url` | 提供 JDBC URL 或应用 DataSource |
| `FLYDB-4003` | clean 被禁用 | `clean-disabled=true` | 明确设置 false 并完成二次确认 |

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
| `5` | 用户中断（SIGINT） |
