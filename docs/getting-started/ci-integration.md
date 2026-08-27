# CI 集成指南

把 Flydb CLI 接入 GitHub Actions 与 Jenkins。多环境配置组织（每数据库×环境一份 `flydb.conf`、密码分层注入、脚本仓库布局）见[多数据库多环境自动化](multi-environment.md)，本文不重复；命令与选项以[CLI 命令参考](../reference/commands.md)为准，机器输出 schema 见[JSON 输出参考](../reference/json-output.md)。

## 1. 通用原则

- **机器消费一律加 `--json`**：stdout 是单行 JSON 信封，进度日志和错误原文只在 stderr，`| jq` 管道是干净的。
- **退出码做门禁，错误码做分流**：`2` 校验失败直接阻断；`3` 锁冲突可自动重试（`flydb.lock-timeout-seconds` 按最长迁移时长设置）；`4` 配置错误回退配置阶段修复；`--json` 下用 `.error.code` 精确区分（如 `FLYDB-2004` 需人工 repair，`FLYDB-3001` 可重试）。
- **密码只走环境变量**：CI secret 系统注入 `FLYDB_PASSWORD`（或 `flydb.password=${env:VAR}`、`flydb.password.file`），不使用 `-p/--password`。
- **所有环境同一套命令序列**：`version` → `validate` → `--dry-run migrate` →（审批门）→ `migrate` → `info` → `validate`；环境晋升只是换 `-c` 指向的 conf。
- Runner 需要 Java 8+，且预置安装目录 `drivers/` 或可访问的 Maven 私服（见[多环境指南 §7](multi-environment.md#7-驱动分发与离线执行机)）。

## 2. GitHub Actions

```yaml
name: database-migration
on:
  push:
    branches: [main]

jobs:
  migrate:
    runs-on: ubuntu-latest
    env:
      FLYDB_HOME: ${{ runner.temp }}/flydb-cli-0.3.3   # ZIP 内的版本化根目录
      CONF: deploy/flydb.mysql.uat.conf
    steps:
      - uses: actions/checkout@v4

      - name: 安装 Flydb CLI（版本随仓库锁定）
        run: |
          curl -fsSL -o flydb.zip \
            "https://github.com/zzxCoding/Flydb/releases/download/v0.3.3/flydb-cli-0.3.3.zip"
          unzip -q flydb.zip -d "$RUNNER_TEMP"
          "$FLYDB_HOME/bin/flydb" version

      # 测试库可用 service container；生产库用自托管 runner 走内网
      - name: 启动测试数据库
        run: docker run -d --name mysql -e MYSQL_ROOT_PASSWORD=root \
            -e MYSQL_DATABASE=app -p 3306:3306 mysql:8.0

      - name: 校验
        env:
          FLYDB_PASSWORD: ${{ secrets.FLYDB_DB_PASSWORD }}
        run: "$FLYDB_HOME/bin/flydb" -c "$CONF" --json validate

      - name: 预演并留档待执行清单
        env:
          FLYDB_PASSWORD: ${{ secrets.FLYDB_DB_PASSWORD }}
        run: |
          "$FLYDB_HOME/bin/flydb" -c "$CONF" --json --dry-run migrate \
            | tee dry-run.json | jq -r '.migrations[].script'
          # 待执行脚本数与预期不符时在此失败：
          test "$(jq '.migrations | length' dry-run.json)" -gt 0

      - uses: actions/upload-artifact@v4
        with:
          name: dry-run-plan
          path: dry-run.json

      # ── 生产环境在此设置审批门（environment protection rules）后执行 ──

      - name: 迁移（锁冲突自动重试一次）
        env:
          FLYDB_PASSWORD: ${{ secrets.FLYDB_DB_PASSWORD }}
        run: |
          set +e
          "$FLYDB_HOME/bin/flydb" -c "$CONF" --json migrate | tee migrate.json
          code=${PIPESTATUS[0]}
          set -e
          # 3 = 锁冲突/超时：等待后重试一次；其余失败码直接退出
          if [ "$code" -eq 3 ]; then
            sleep 30
            "$FLYDB_HOME/bin/flydb" -c "$CONF" --json migrate
          elif [ "$code" -ne 0 ]; then
            jq -r '.error' migrate.json >&2 || true
            exit "$code"
          fi

      - name: 状态留档并复核
        env:
          FLYDB_PASSWORD: ${{ secrets.FLYDB_DB_PASSWORD }}
        run: |
          "$FLYDB_HOME/bin/flydb" -c "$CONF" --json info > info.json
          jq -r '.migrations[] | "\(.version // "(可重复)") \(.state)"' info.json
          "$FLYDB_HOME/bin/flydb" -c "$CONF" --json validate
```

要点：

- **版本锁定的安装步骤就是版本一致性检查**：release 资产名与 `bin/flydb version` 输出（`--json` 下读 `.version` 与 `.protocolVersion`）双重核对。
- `dry-run.json`、`info.json` 作为制品留档，供审批与审计回看；审批门用 GitHub Environments 的 required reviewers，而不是在脚本里绕过。
- 生产库建议 `runs-on` 自托管 runner + 环境 protection，数据库不对公网暴露。

## 3. Jenkins

声明式流水线，凭据经 `withCredentials` 注入环境变量，命令行与日志中不出现明文密码：

```groovy
pipeline {
    agent any

    environment {
        FLYDB_HOME = '/opt/flydb-cli'          // 预置发行包与 drivers/
        CONF       = 'deploy/flydb.dm.prod.conf'
    }

    stages {
        stage('校验') {
            steps {
                withCredentials([string(credentialsId: 'flydb-db-password', variable: 'DB_PASSWORD')]) {
                    sh '''#!/bin/bash
                        set -euo pipefail
                        export FLYDB_PASSWORD="$DB_PASSWORD"
                        "$FLYDB_HOME/bin/flydb" -c "$CONF" version
                        "$FLYDB_HOME/bin/flydb" -c "$CONF" --json validate
                    '''
                }
            }
        }
        stage('预演') {
            steps {
                withCredentials([string(credentialsId: 'flydb-db-password', variable: 'DB_PASSWORD')]) {
                    sh '''#!/bin/bash
                        set -euo pipefail
                        export FLYDB_PASSWORD="$DB_PASSWORD"
                        "$FLYDB_HOME/bin/flydb" -c "$CONF" --json --dry-run migrate > dry-run.json
                        jq -r '.migrations[].script' dry-run.json
                    '''
                }
            }
        }
        stage('审批') {
            steps { input '核对 dry-run 清单与目标库后确认执行迁移' }
        }
        stage('迁移') {
            steps {
                withCredentials([string(credentialsId: 'flydb-db-password', variable: 'DB_PASSWORD')]) {
                    sh '''#!/bin/bash
                        set -euo pipefail
                        export FLYDB_PASSWORD="$DB_PASSWORD"
                        set +e
                        "$FLYDB_HOME/bin/flydb" -c "$CONF" --json migrate | tee migrate.json
                        code=${PIPESTATUS[0]}
                        set -e
                        if [ "$code" -eq 3 ]; then
                            sleep 30
                            "$FLYDB_HOME/bin/flydb" -c "$CONF" --json migrate
                        elif [ "$code" -ne 0 ]; then
                            exit "$code"
                        fi
                    '''
                }
            }
        }
        stage('复核') {
            steps {
                withCredentials([string(credentialsId: 'flydb-db-password', variable: 'DB_PASSWORD')]) {
                    sh '''#!/bin/bash
                        set -euo pipefail
                        export FLYDB_PASSWORD="$DB_PASSWORD"
                        "$FLYDB_HOME/bin/flydb" -c "$CONF" --json info > info.json
                        jq -r '.migrations[] | "\\(.version) \\(.state)"' info.json
                        "$FLYDB_HOME/bin/flydb" -c "$CONF" --json validate
                    '''
                }
                archiveArtifacts artifacts: 'dry-run.json,migrate.json,info.json'
            }
        }
    }
}
```

要点：

- **凭据用 `withCredentials` 而非 `sh` 内联**：Jenkins 会掩码凭据变量在日志中的出现，但不要因此把密码写进文件或参数。
- `input` 步骤即生产审批门；预演制品先行归档，审批人核对的是同一份清单。
- 迁移失败的修复策略（`repair`、脚本修正）由人确认后执行，不要在流水线里自动 `repair`——它会修改历史表。

## 4. 常见分流逻辑

| 场景 | 判断 | 动作 |
|---|---|---|
| 校验失败 | 退出码 `2` / `error.code` `FLYDB-2003`、`FLYDB-2004` | 阻断并告警，人工核对 checksum/失败记录 |
| 锁冲突 | 退出码 `3` / `FLYDB-3001` | 有限次退避重试；持续失败说明有并发迁移者 |
| 配置错误 | 退出码 `4` / `FLYDB-4001`、`FLYDB-4002` | 回退配置阶段，不要重试 |
| 连接失败 | 退出码 `1` / `FLYDB-1001` | 检查网络/数据库状态后重试 |
| 参数写错 | 退出码 `4` 且 `error.code` 为 `null` | 修流水线脚本本身 |

相关页面：[多数据库多环境自动化](multi-environment.md)、[JSON 输出参考](../reference/json-output.md)、[错误码参考](../reference/errors.md)、[CLI 命令参考](../reference/commands.md)。
