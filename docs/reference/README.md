# Flydb 参考文档

这里记录可直接查阅的稳定接口与运行约定：

- [配置项参考](configuration.md)：CLI、环境变量、`flydb.conf` 与 Spring Boot `flydb.*` 配置。
- [错误码参考](errors.md)：稳定错误码、常见原因、修复动作和 CLI 退出码。
- [CLI 命令参考](commands.md)：全局选项、命令、锁范围和常用流程。
- [命令与 CLI 设计](../design/06-config-cli.md)：命令语义、驱动加载和发行包布局。

配置优先级统一为：

```text
CLI 参数 > FLYDB_* 环境变量 > flydb.conf > 内置默认值
```
