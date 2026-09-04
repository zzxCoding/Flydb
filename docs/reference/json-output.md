# JSON 输出参考

`--json` 让任何程序（CI、IDE、外部宿主、Agent）稳定消费 Flydb CLI 的结果。设计与兼容承诺见[机器契约设计](../design/10-machine-contract.md)；本文是 schema 事实来源。

## 通道与格式

- `--json` 是全局选项，作用于所有命令，可与 `--dry-run`、版本选择、路径过滤组合。
- **stdout 恰好一行紧凑 JSON**（以换行结尾），可直接 `| jq`；**stderr 只有人类诊断**（逐脚本/语句级进度、失败执行快照、错误原文、`-X` 堆栈）。执行遥测不会写入 stdout，也不改变信封 schema。
- 输出为 UTF-8。`--help` 输出永远是文本。
- 退出码不变（见[错误码参考](errors.md#cli-退出码)）；`status` 与 `exitCode` 字段和进程退出码一致。

## 统一信封

```bash
$ FLYDB_PASSWORD='...' bin/flydb --json migrate
{"protocolVersion":1,"command":"migrate","status":"success","exitCode":0,
 "executed":["V2__add_order.sql"],"targetVersionReached":"2",
 "totalExecutionTimeMillis":842,"warnings":[]}
```

（实际输出为单行，上面为便于阅读折行。）

失败时 `status` 为 `error`，`error` 对象携带错误码、脱敏详情与校验问题清单：

```bash
$ bin/flydb --json migrate
{"protocolVersion":1,"command":"migrate","status":"error","exitCode":4,
 "error":{"code":"FLYDB-4002","detail":"必须提供 flydb.url","problems":[]}}
```

| 字段 | 说明 |
|---|---|
| `protocolVersion` | 契约版本，当前 `1` |
| `command` | 叶子命令名；解析期失败无法定位时为 `null` |
| `status` | `success` / `error` |
| `exitCode` | 与进程退出码一致 |
| `error.code` | `FLYDB-xxxx` 错误码；参数用法错误与非 Flydb 异常为 `null`（凭 `exitCode` 分类） |
| `error.detail` | 动态详情；密码与 URL 内嵌凭据已替换为 `****` |
| `error.problems` | 校验类失败逐条 `{code, detail}`；其余为 `[]` |

## 各命令载荷

| 命令 | 字段 |
|---|---|
| `version` | `version` |
| `migrate` | `executed`、`targetVersionReached`、`totalExecutionTimeMillis`、`warnings` |
| `--dry-run migrate` / `--dry-run undo` | `dryRun:true`、`plan.{algorithm,direction,id,targetVersion,migrationCount,statementCount}`（Plan Artifact v1，见[Plan Artifact 设计](../design/11-plan-artifact.md)）、`migrations[].{script,type,version,description,checksum,statementCount,statements[].{lineNumber,sql}}` |
| `info` | `databaseName`、`url`（脱敏）、`historyTable`、`current`、`migrations[]` |
| `validate` | 无载荷 |
| `baseline` | `baselineVersion` |
| `repair` | `removedFailedRecords`、`alignedChecksums` |
| `undo` | `undoneVersion`、`executionTimeMillis` |
| `init` | `createdFiles`（相对路径） |
| `clean` | 无载荷 |

取值约定：状态 token 为 `PENDING`、`OUT_OF_ORDER`、`SUCCESS`、`FAILED`、`MISSING`、`OUTDATED`、`FUTURE`、`BASELINE`、`UNDONE`；类型 token 为 `SQL`、`JDBC`、`BASELINE`、`UNDO_SQL`；`installedOn` 为 ISO-8601 本地时间；可重复迁移的 `version` 为 `null`；未知或不适用的数值为 `null`。

## 稳定性承诺

同一 `protocolVersion` 内只新增字段、不改名、不删除、不改类型或语义；**消费者必须忽略未知字段**。破坏性变更会递增 `protocolVersion` 并在 CHANGELOG 说明。字段顺序固定但消费者不得依赖。

`--json` 模式不发起任何交互：密码缺失、`clean` 未带 `--force`、`init` 未带 `--yes` 时直接按非交互规则报错（`FLYDB-4002`/`FLYDB-4003`）。CI 与脚本中请通过 `FLYDB_PASSWORD`、`${env:VAR}` 或 `flydb.password.file` 提供密码。

例外：Ctrl+C（退出码 5）直接终止进程，不保证输出信封。

## CI 中的用法

```bash
# 门禁：dry-run 清单核对
bin/flydb -c deploy/flydb.mysql.uat.conf --json --dry-run migrate \
  | jq -r '.migrations[].script'

# 失败分流：错误码区分可重试与需人工
result=$(bin/flydb -c "$CONF" --json migrate)
code=$(echo "$result" | jq -r '.error.code')   # FLYDB-3001 锁冲突可重试
```

完整流水线示例见[CI 集成指南](../getting-started/ci-integration.md)。
