# 01 模块划分与仓库布局

> [← 返回总览](00-overview.md) | 下一篇：[02 领域模型与 API](02-domain-api.md)

## 1. Maven 多模块布局

```
flydb/                                   根 POM（packaging=pom，统一版本与插件管理）
├── pom.xml
├── flydb-core/                          纯 Java 8，零第三方运行时依赖
├── flydb-cli/                           picocli 可执行发行包（Java 8）
├── flydb-spring-boot-2-starter/         Spring Boot 2.7.x / Java 8
├── flydb-spring-boot-3-starter/         Spring Boot 3.x / Java 17（可选交付，见 07）
└── flydb-integration-tests/             Testcontainers 集成测试矩阵（可用新 JDK，构建时可跳过）
```

| 模块 | Java 目标 | 运行时依赖 | 发布到 Maven 仓库 |
|---|---|---|---|
| `flydb-core` | 8 | **无**（仅 JDK 自带 API） | 是 |
| `flydb-cli` | 8 | flydb-core、picocli | 是（同时发布 zip 发行包） |
| `flydb-spring-boot-2-starter` | 8 | flydb-core、spring-boot 2.7（provided 语义由 starter 机制承接） | 是 |
| `flydb-spring-boot-3-starter` | 17 | flydb-core、spring-boot 3.x | 是 |
| `flydb-integration-tests` | 17（或 CI 环境 JDK） | 测试期：testcontainers、junit5、各数据库驱动 | 否（`maven.deploy.skip=true`） |

根 POM 关键配置：
- `maven-compiler-plugin`：core/cli/starter-2 使用 `<release>8</release>`（在新 JDK 上编译也能保证不误用高版本 API，比 source/target 组合更严格）。
- `maven-enforcer-plugin`：禁止 flydb-core 出现任何非 test 作用域依赖（用 `bannedDependencies` 白名单强制"零依赖"承诺，防止未来 PR 无意打破）。
- `jacoco-maven-plugin`：行覆盖率 ≥80% 门禁（integration-tests 模块除外，其覆盖率并入统计但不单独设卡）。

## 2. 是否按数据库拆模块？——结论：MVP 不拆，靠 SPI 保留拆分能力

**决策：内置方言全部放在 `flydb-core` 内部，但强制通过 ServiceLoader SPI 边界解耦。**

理由：

1. **规模不值得**：每个具体方言预计 100~300 行（家族基类吸收了大部分逻辑）。拆成 8 个 Maven 模块意味着 8 份 POM、8 次发布、版本对齐成本，收益为零（YAGNI）。
2. **真正需要隔离的是驱动依赖而非方言代码**。方言代码对 JDBC 驱动的 Java 类**零编译期依赖**——只使用 `java.sql.*` 接口 + 驱动类名字符串。因此 core 不会被任何驱动的许可证（MySQL Connector/J 的 GPL+FOSS 例外、OceanBase 客户端的 LGPL）传染。
3. **SPI 保留退路**：若未来某方言确需独立（如某国产库要求额外许可协议才能重分发适配代码），可平移为独立 jar，core 零改动。

### 2.1 SPI 机制

`flydb-core` 定义扩展点并用 `java.util.ServiceLoader` 加载：

```
flydb-core/src/main/resources/META-INF/services/
└── com.flydb.core.dialect.DatabaseType      # 外部方言的实现类清单
```

二期新增数据库（神通、GBase、瀚高）的接入方式：**新建独立 jar（如 `flydb-dialect-shentong`），仅依赖 flydb-core 的 SPI 接口，自带 `META-INF/services` 注册文件**。用户将该 jar 与对应驱动放入 CLI 的 `drivers/` 目录（或加入应用 classpath）即可生效——这是"YAGNI 现在、可扩展未来"的具体落点，也是验证 SPI 设计是否真正解耦的验收方式。

`MigrationResolver` 与 `Callback` 同样是 SPI 扩展点（见 [02 §5](02-domain-api.md)、[05 §8](05-commands.md)），加载机制一致。

## 3. flydb-core 包结构

```
com.flydb.core
├── api/            Flydb 门面、FlydbConfiguration、MigrateResult 等公共 API
├── command/        MigrateCommand / InfoCommand / ValidateCommand / BaselineCommand
│                   / RepairCommand / CleanCommand / UndoCommand（每命令一个类）
├── migration/      MigrationVersion、ResolvedMigration、AppliedMigration、
│                   MigrationInfo、MigrationType、MigrationState
├── resolver/       MigrationResolver SPI、SqlMigrationResolver、JavaMigrationResolver、
│                   命名解析（MigrationNamePattern）、ChecksumCalculator
├── executor/       SqlScriptParser、SqlScriptLexer、SqlStatement、
│                   SqlStatementBuilderConfig、MigrationExecutor
├── history/        SchemaHistory（历史表仓储：ensureExists/findAll/insert/repair）
├── dialect/        DatabaseType SPI、DatabaseTypeRegistry、Database、三家族实现
│                   PostgreSQLFamilyDatabase、MySQLFamilyDatabase、OracleFamilyDatabase
│                   及各产品 Database(+Type)
├── lock/           MigrationLock、advisory/table lock 实现
├── callback/       Callback SPI、Event 枚举、SqlCallbackResolver
├── config/         ConfigLoader（Properties/env/显式值合并）、PlaceholderReplacer
├── exception/      FlydbException、FlydbValidationException、ErrorCode（错误码枚举）
└── log/            Log、LogFactory（极简日志抽象，见 §4）
```

## 4. 日志方案

**core 不依赖 SLF4J**（零依赖承诺），提供极简日志抽象：

```java
public interface Log {
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message, Throwable t);
}
```

- `LogFactory` 默认输出到 `System.err`（带级别过滤）；
- 通过 `LogFactory.setLogCreator(...)` 允许适配层替换实现：
  - `flydb-cli`：接管为带颜色/`--quiet`/`-X` 控制的控制台输出；
  - starter：桥接到 SLF4J（starter 环境必有 SLF4J，在 starter 模块内实现桥接类，core 不感知）。
- 这是 Flyway 同款成熟做法，实现成本约 100 行。

## 5. 依赖与许可证策略

| 事项 | 决策 |
|---|---|
| flydb 自身许可证 | Apache-2.0（沿用根目录 LICENSE，并在发行包附带 NOTICE） |
| JDBC 驱动 | **一律不作为任何模块的运行时/optional/provided 依赖**。CLI 靠 `drivers/` 目录动态加载（[06 §6](06-config-cli.md)）；库用户自行引入驱动；integration-tests 以 test scope 引入用于测试 |
| picocli | 仅 flydb-cli 依赖；Apache 2.0，最低支持 Java 5（可信度：高，picocli 官方 GitHub） |
| YAML | 不引入。配置文件用 Properties（JDK 内置解析），理由见 [06 §1](06-config-cli.md) |
| 驱动坐标速查（写入 CLI 的 drivers/README） | `com.dameng:DmJdbcDriver18`、`cn.com.kingbase:kingbase8`（选不带 .jre 后缀的 JDK8+ 版本）、`org.opengauss:opengauss-jdbc`、`com.oceanbase:oceanbase-client`（官方声明基于 Java 8 开发，可信度：高）、`com.mysql:mysql-connector-j`（官方声明支持 JRE 8+，可信度：高）、`org.postgresql:postgresql` 42.x（JRE 8+，可信度：高） |

**驱动字节码版本防护**：达梦/金仓/openGauss 驱动的 Java 8 兼容性未见官方明文（可信度：中），实施阶段须用 `jdeps`/`javap` 实测 class 文件版本，并在 integration-tests 的 CI 中固化为校验步骤（见 [09 §5](09-implementation-plan.md)），防止驱动升级悄悄引入高版本字节码。

## 6. 版本与发布

- 版本号：`0.2.0` 起步（0.x 表示初始开发期、公共 API 未承诺稳定；1.0 留给迁移引擎与机器契约稳定后发布），语义化版本。
- 所有模块统一版本号，由根 POM `revision` 属性管理（`flatten-maven-plugin` 处理发布）。
- 发布物：
  1. Maven 仓库：flydb-core、flydb-cli（jar）、两个 starter；
  2. GitHub Releases：`flydb-cli-<版本>.zip` 发行包（布局见 [06 §7](06-config-cli.md)）。

## 7. 旧代码处置

新布局与旧代码不共存。实施阶段第一步（见 [09 §3 阶段 1](09-implementation-plan.md)）：

- 删除：`src/`（整个旧代码树）、`flydb.sh`、旧 `application.yml`/`db-connections.yml`/`logback.xml`、旧单模块 `pom.xml`（替换为根 POM）、`target/`、`logs/`。
- 保留：`LICENSE`（Apache-2.0）、`NOTICE`、`mvnw`/`mvnw.cmd`（升级 wrapper 版本）、`README.md`（重写，见 [06 §8](06-config-cli.md) 文档规划）。
- 旧 README 中值得继承的内容（命名规范说明、FAQ 结构）在重写时吸收，但所有"已支持数据库"的表述必须与实际实现一致——旧 README 宣传与代码脱节是本次重写要根治的问题之一。
