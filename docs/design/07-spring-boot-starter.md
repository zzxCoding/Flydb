# 07 Spring Boot Starter 设计

> [← 06 配置与 CLI](06-config-cli.md) | [返回总览](00-overview.md) | 下一篇：[08 测试与路线图](08-testing-roadmap.md)

目标体验：**引入依赖即在应用启动时自动迁移**，配置项命名贴近 `spring.flyway.*` 习惯，现有 Flyway 用户可低成本切换。

## 1. 双模块策略

| 模块 | 目标 | Java | 自动配置注册方式 |
|---|---|---|---|
| `flydb-spring-boot-2-starter` | Spring Boot 2.7.x | 8 | `META-INF/spring.factories` |
| `flydb-spring-boot-3-starter` | Spring Boot 3.x | 17 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |

**决策：两个模块各自独立维护一份自动配置代码（每份约 150~250 行），不做共享源码工程。**

- 评估过 Maven resource 过滤、multi-release jar、共享内核模块等方案，对两份小体量代码而言投入产出比不划算（YAGNI）。重复代码规模显著增长时二期再抽共享模块。
- 两者都只依赖 `flydb-core`（Java 8 字节码在 Boot 3/JDK 17 环境天然兼容，无需处理）。
- Boot 2.7 已于 2023-11 结束官方 OSS 支持——这恰是"服务 Java 8 存量用户"定位的一部分（这批用户本就停在 Boot 2.x），在 README 中如实告知、管理预期即可，不构成技术障碍。

## 2. 属性设计（前缀 `flydb.*`）

```yaml
flydb:
  enabled: true                  # 默认 true；false 则完全不装配
  locations: classpath:db/migration
  table: flydb_schema_history
  baseline-on-migrate: false
  validate-on-migrate: true
  out-of-order: false
  clean-disabled: true
  lock-timeout-seconds: 60
  database-type:                 # 可选，跳过自动探测
  placeholders:
    dept: sales
  url:                           # 可选三件套：不填则复用应用主 DataSource
  user:
  password:
```

- 属性类 `FlydbProperties`（`@ConfigurationProperties(prefix = "flydb")`），字段与 [06 §2](06-config-cli.md) 清单一致，提供 IDE 补全元数据（`spring-boot-configuration-processor`）。
- 命名刻意对齐 `spring.flyway.*` 的习惯（`locations`/`baseline-on-migrate`/...），从 Flyway 迁移时基本是前缀替换。

## 3. 自动配置类

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)   // Boot 2 用 @Configuration + spring.factories
@ConditionalOnClass(Flydb.class)
@ConditionalOnProperty(prefix = "flydb", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlydbProperties.class)
public class FlydbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Flydb flydb(FlydbProperties properties, ObjectProvider<DataSource> dataSource) {
        // properties.url 非空 → 构建迁移专用 DriverDataSource
        // 否则复用应用主 DataSource（缺失则 FLYDB-4002 快速失败）
    }

    @Bean
    @ConditionalOnMissingBean
    public FlydbMigrationInitializer flydbMigrationInitializer(Flydb flydb) {
        return new FlydbMigrationInitializer(flydb);
    }
}
```

### 3.1 迁移时机

`FlydbMigrationInitializer implements InitializingBean`——在 bean 初始化期执行 `flydb.migrate()`（对齐 Flyway Spring 集成的成熟做法），并通过依赖关系保证顺序：

- `@AutoConfigureBefore`（或 Boot 3 的 `beforeInitialization` 依赖声明）确保先于 JPA `EntityManagerFactory`、`JdbcTemplate` 等依赖 schema 的 bean 初始化；
- 实施时提供 `FlydbDataSourceDependencyPostProcessor`（模式同 Boot 内置的 Flyway 依赖处理器）：凡依赖 DataSource 的 bean 自动依赖 initializer，避免用户手工 `@DependsOn`。
- 迁移失败 → 抛出异常中断应用启动（快速失败；不允许"带着错误 schema 启动"）。

### 3.2 独立迁移账号（企业安全实践）

显式配置 `flydb.url/user/password` 时构建**迁移专用 DataSource**：迁移用高权限 DDL 账号，应用运行时用低权限账号。这是信创客户（等保合规）常见要求，作为一等能力支持并写入文档示例。

## 4. 日志桥接

starter 模块内注册 `LogFactory.setLogCreator(...)` 将 core 的日志抽象（[01 §4](01-modules.md)）桥接到 SLF4J，迁移过程以标准应用日志输出：

```
INFO  c.f.starter : Flydb 2.0.0 · openGauss · 发现 3 个待执行迁移
INFO  c.f.starter : 执行 V2__add_status_column.sql (45ms)
INFO  c.f.starter : 迁移完成，当前版本 2，总耗时 128ms
```

## 5. 测试

- 单测：`ApplicationContextRunner` 验证条件装配矩阵（enabled=false 不装配 / 无 DataSource 快速失败 / 自定义 Flydb bean 时让位 / url 三件套构建独立数据源）。
- 集成：starter + H2 内存库跑通启动迁移（H2 仅作为 starter 装配冒烟，不属于方言支持矩阵）；真实方言组合在 flydb-integration-tests 覆盖（[08 §2](08-testing-roadmap.md)）。

## 6. 与 CLI/API 的一致性约束

starter 只是装配层：所有行为（校验、锁、失败处理）与 API/CLI 完全一致，不在 starter 内重复实现任何迁移逻辑。starter 特有的仅有：属性绑定、DataSource 装配、时机编排、日志桥接四件事。
