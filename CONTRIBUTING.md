# 贡献 Flydb

感谢你帮助改进 Flydb。本指南面向准备提交 Issue 或 Pull Request 的开发者；架构约束以
[设计总览](./docs/design/00-overview.md)及其链接的设计文档为准。

## 开始之前

- 搜索现有 [Issues](https://github.com/zzxCoding/Flydb/issues)，避免重复问题。
- Bug 请提供 Flydb、Java、数据库和 JDBC 驱动版本，以及可复现的最小步骤。
- 不要提交密码、带凭据的 JDBC URL、生产数据、厂商 JDBC 驱动或其他受限制品。
- 安全漏洞不要公开建 Issue，请遵循[安全策略](./SECURITY.md)。

## 本地构建

完整 reactor 使用 JDK 17：

```bash
./mvnw -B verify
```

core、CLI、Boot 2 starter 和 Boot 2 示例必须保持 Java 8 字节码。涉及这些模块或发行边界时，额外运行：

```bash
./scripts/check-bytecode.sh 52 \
  flydb-core/target/classes flydb-cli/target/classes \
  flydb-spring-boot-2-starter/target/classes examples/boot2-demo/target/classes
./scripts/check-bytecode.sh 61 \
  flydb-spring-boot-3-starter/target/classes examples/boot3-demo/target/classes
```

集成测试可能启动 Docker 数据库。达梦、KingbaseES、Oracle 等授权数据库只有在你拥有合法驱动和测试实例时才能运行；提交中不得重新分发驱动。

## 修改约束

- 优先在公共边界补回归测试，再修改实现。
- `flydb-core` 不得增加非 `test` 作用域依赖。
- 公共 API、CLI、配置键、错误码、驱动加载或数据库支持范围发生变化时，同步更新测试和 `docs/reference`。
- 设计契约与实现冲突时，在 PR 中说明冲突和处理方式，不要静默偏离。
- 数据库兼容性结论必须写清验证层级；单元测试或兼容家族测试不等于厂商认证。

## 提交 Pull Request

1. 从最新 `main` 创建聚焦单一目标的分支。
2. 提交前运行相关定向测试和完整 `./mvnw -B verify`。
3. 运行 `git diff --check`，确认没有意外文件或凭据。
4. 在 PR 中说明变更原因、行为影响、验证命令和未验证边界。
5. 若改变用户可见行为，同步更新中英文 README 或相应参考文档。

提交贡献即表示你同意按仓库的 [Apache License 2.0](./LICENSE) 提供该贡献。
