# CLI 命令参考

CLI 形式为：

```text
flydb [全局选项] <命令> [命令选项]
```

## 全局选项

| 选项 | 说明 |
|---|---|
| `-c, --config <file>` | 显式指定 `flydb.conf` |
| `-u, --url <jdbc-url>` | JDBC URL |
| `--user <name>` | 数据库用户 |
| `-p, --password <value>` | 密码；生产推荐环境变量或密码文件 |
| `--driver <class>` | 显式 JDBC Driver 类名 |
| `--database-type <name>` | 显式方言名 |
| `-l, --locations <locations>` | 迁移位置，逗号分隔 |
| `--encoding <charset>` | SQL 文件编码 |
| `--table <name>` | 历史表名 |
| `-D<key>=<value>` | SQL 占位符 |
| `-X, --debug` | 输出完整异常栈 |
| `-q, --quiet` | 只输出必要结果和错误 |
| `--color=auto\|always\|never` | 控制终端颜色 |
| `-n, --dry-run` | `migrate`/`undo` 只解析、打印，不执行 SQL |

## 命令

| 命令 | 动作 | 持有迁移锁 |
|---|---|---:|
| `migrate` | 校验并执行待迁移脚本 | 是 |
| `info` | 输出本地脚本与历史记录状态 | 否 |
| `validate` | 校验 checksum、失败记录、缺失/未来迁移 | 否 |
| `baseline` | 写入一条 baseline 记录，不执行 SQL | 是 |
| `repair` | 清除失败记录、对齐 checksum | 是 |
| `clean` | 删除目标 schema 的表、视图、序列 | 是 |
| `undo` | 撤销最近一次版本化迁移 | 是 |
| `init` | 生成 `flydb.conf`、迁移目录和驱动说明 | 否 |
| `version` | 输出 Flydb 版本 | 否 |

## 常用流程

```bash
bin/flydb init \
  --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user --database-type mysql --yes

FLYDB_PASSWORD='...' bin/flydb --dry-run migrate
FLYDB_PASSWORD='...' bin/flydb migrate
FLYDB_PASSWORD='...' bin/flydb info --color=never
FLYDB_PASSWORD='...' bin/flydb validate
```

迁移脚本命名：`V1__init.sql`、`R__view.sql`、`U1__init.sql`。2.0 起 `R1__...sql` 会报 `FLYDB-2005`；失败记录必须先 `repair`，否则后续 `migrate` 报 `FLYDB-2004`。

`clean` 默认禁用。非交互环境必须同时设置 `--clean-disabled=false` 和 `--force`：

```bash
bin/flydb clean --clean-disabled=false --force
```

退出码和错误码见[错误码参考](errors.md)，配置键见[配置项参考](configuration.md)。
