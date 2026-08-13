# CLI 文档引用地图

`flydb-cli` Skill 不复制命令、配置或数据库矩阵。运行时优先读取当前 Flydb checkout 中的以下文件：

| 需要确认的内容 | 事实来源 |
|---|---|
| 全局选项、命令、锁范围、dry-run | `docs/reference/commands.md` |
| 配置键、环境变量、优先级 | `docs/reference/configuration.md` |
| 错误码和退出码 | `docs/reference/errors.md` |
| 驱动目录、兼容家族和自定义 SPI | `docs/getting-started/jdbc-integration.md` |
| 各内置数据库的驱动、权限和限制 | `docs/getting-started/README.md` 及对应页面 |
| CLI 动态加载和发行包约束 | `docs/design/06-config-cli.md` |

如果这些文件与已安装 CLI 版本不一致，优先以目标版本随附文档为准，并在汇报中指出版本差异。不要把本文件当成 CLI 参数的替代手册。
