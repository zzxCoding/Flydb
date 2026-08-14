# JDBC 数据库快速接入

本页面向需要接入信创数据库、行业数据库或新型 JDBC 数据库的开发者和运维人员。目标是把“拿到驱动后如何跑起来”缩短为一条可复制的路径，并说明什么时候可以复用 MySQL/Oracle 家族、什么时候必须实现自己的方言 SPI。

> Flydb 的驱动和方言是两个独立概念：JDBC 驱动负责建立连接；`DatabaseType` 方言负责 SQL 切分、标识符引用、历史表 DDL、迁移锁、DDL 事务失败语义等。数据库“支持 MySQL/Oracle 语法”并不自动代表这些迁移语义完全相同。

## 1. 先选接入路线

| 目标数据库情况 | 推荐做法 | 需要提供的内容 |
|---|---|---|
| 已有内置方言，且 URL/产品与其一致 | 直接使用内置标识 | JDBC 驱动 JAR；建议显式写 `--database-type` |
| 厂商 URL 不同，但确认迁移语义与 MySQL 家族一致 | 复用 `mysql` | JDBC 驱动 JAR；显式 `--driver` 和 `--database-type mysql` |
| 厂商 URL 不同，但确认迁移语义与 Oracle 家族一致 | 复用 `oracle` | JDBC 驱动 JAR；显式 `--driver` 和 `--database-type oracle` |
| 锁、历史表 DDL、DDL 事务或语句终止符有专有差异 | 实现独立 `DatabaseType` SPI | JDBC 驱动 JAR + 方言 JAR + 唯一方言名 |

当前内置标识包括：`mysql`、`postgresql`、`oracle`、`dm`、`kingbasees`、`opengauss`、`oceanbase` 和 `tidb`。`oceanbase` 会在连接后按 MySQL/Oracle 兼容模式分派；TiDB 虽然使用 MySQL 协议，若 URL 或产品信息存在歧义，建议显式写 `tidb`。

选择兼容家族前至少确认以下项目：

- DDL 是否隐式提交，失败后是否能够回滚；
- 历史表和锁表 DDL 是否可执行，是否需要专用 advisory lock；
- 标识符是反引号、双引号还是大小写敏感；
- 存储过程、触发器、`DELIMITER` 或 PL/SQL 块的结束规则；
- `current schema`、当前用户和系统目录查询是否与家族实现一致。

只确认“普通 CRUD SQL 能执行”还不够。无法确认时，应使用独立 SPI 方言或先在授权测试实例完成契约验证。

## 2. CLI：驱动 JAR + 显式方言

CLI 不捆绑厂商驱动。启动时先扫描安装目录下的 `drivers/*.jar`，再检查运行时 classpath、Maven 本地仓库和 `~/.flydb/drivers` 缓存；仍未找到时，按 `~/.m2/settings.xml` 的 mirror、激活 Profile 仓库、server 认证和 proxy 下载。对标准 URL，Flydb 可以推断驱动类和已登记的固定坐标；厂商 URL 通常应显式指定 `--driver` 和 `--driver-coordinate`。

### 2.1 复用 MySQL/Oracle 家族

下面以一个使用 MySQL 兼容语法、但 URL 和驱动类均为厂商自定义的数据库为例。这里的 `vendor`、`vendor.jdbc.Driver` 只是占位符，请替换为厂商实际值：

```bash
unzip flydb-cli-0.2.0.zip
cd flydb-cli-0.2.0

cp /path/to/vendor-jdbc.jar drivers/

bin/flydb init \
  --url 'jdbc:vendor://db.example.com:1234/app' \
  --user flydb_user \
  --driver 'vendor.jdbc.Driver' \
  --database-type mysql \
  --yes

export FLYDB_PASSWORD='replace-me'
bin/flydb validate
bin/flydb --dry-run migrate
bin/flydb migrate
```

如果驱动已经发布到公司 Maven 私服，可以省略 `cp`。确认 `settings.xml` 已配置 mirror/server 后，在生成的配置中填写私服坐标：

```properties
flydb.driver=vendor.jdbc.Driver
flydb.driver-coordinate=com.company.jdbc:vendor-driver:3.2.1
```

内网完全禁止联网时设置 `flydb.offline=true`；Flydb 仍会读取 Maven 本地仓库和已有缓存。`mirrorOf=*` 会接管 Central，Flydb 不会绕过公司私服直连公网。

`init --driver` 会把 `flydb.driver` 写入当前目录的 `flydb.conf`，后续命令无需重复传参。密码可以直接写入 `flydb.password`（仅建议本地临时测试），生产和共享环境使用 `FLYDB_PASSWORD`、`${env:DB_PASSWORD}` 或 `flydb.password.file`。

Oracle 兼容数据库的命令只需把方言换成 `oracle`：

```bash
bin/flydb init \
  --url 'jdbc:vendor://db.example.com:1521/app' \
  --user flydb_user \
  --driver 'vendor.jdbc.Driver' \
  --database-type oracle \
  --yes
```

如果目标是原生达梦、OceanBase 或其他已内置产品，优先使用产品标识，而不是仅凭语法选择家族：

```bash
# 原生达梦
bin/flydb init --url 'jdbc:dm://db.example.com:5236' \
  --user SYSDBA --driver dm.jdbc.driver.DmDriver --database-type dm --yes

# OceanBase：由 oceanbase 方言在连接后判断 MySQL/Oracle 模式
bin/flydb init --url 'jdbc:oceanbase://db.example.com:2883/app' \
  --user flydb_user --driver com.oceanbase.jdbc.Driver \
  --database-type oceanbase --yes
```

标准 URL 的驱动类可以省略（例如 `jdbc:mysql:`、`jdbc:postgresql:`、`jdbc:oracle:`）；Flydb 会优先复用本地 Maven 仓库，必要时按 Maven 有效私服/镜像获取。无法推断的 URL 若未指定 `--driver`，或小众驱动没有提供 `--driver-coordinate` 且本地也找不到，会报带完整解析轨迹的 `FLYDB-1003`。

### 2.2 CLI 加载自定义方言

没有合适的内置方言时，把两个 JAR 放入同一个目录：

```text
flydb-cli-0.2.0/
└── drivers/
    ├── vendor-jdbc.jar
    └── flydb-dialect-vendorx.jar
```

方言 JAR 需要：

1. 编译时依赖与 CLI 相同版本的 `flydb-core`；
2. 实现 `com.flydb.core.dialect.DatabaseType`；
3. 在 `META-INF/services/com.flydb.core.dialect.DatabaseType` 中写入实现类的全限定名；
4. 使用不会与内置方言重复的 `name()`，例如 `vendorx`；
5. 不要把另一份 `flydb-core` 打进方言 JAR，避免类加载器出现重复版本。

最小结构如下（省略 import；真实项目还应覆盖厂商专有差异）：

```java
public final class VendorXDatabaseType implements DatabaseType {
    @Override public String name() { return "vendorx"; }

    @Override public int priority() { return 100; }

    @Override public boolean handlesUrl(String url) {
        return url != null && url.startsWith("jdbc:vendorx:");
    }

    @Override public boolean handlesConnection(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName()
                .toLowerCase(java.util.Locale.ROOT).contains("vendorx");
    }

    @Override public Database createDatabase(Connection connection,
                                             FlydbConfiguration configuration) {
        return new VendorXDatabase(connection);
    }

    private static final class VendorXDatabase extends MySQLFamilyDatabase {
        VendorXDatabase(Connection connection) { super("VendorX", connection); }
    }
}
```

如果数据库只是 MySQL 家族兼容，可以从 `MySQLFamilyDatabase` 继承；Oracle 兼容可以从 `OracleFamilyDatabase` 继承。若锁、历史表 DDL、DDL 事务或语句切分不同，应实现自己的 `Database`，不要为了“能连接”而错误复用家族基类。

服务注册文件内容：

```text
# META-INF/services/com.flydb.core.dialect.DatabaseType
com.example.flydb.VendorXDatabaseType
```

放好 JAR 后初始化：

```bash
bin/flydb init \
  --url 'jdbc:vendorx://db.example.com:1234/app' \
  --user flydb_user \
  --driver 'vendorx.jdbc.Driver' \
  --database-type vendorx \
  --yes
```

CLI 会把 `drivers/` 下的 JAR 加入上下文类加载器，`ServiceLoader` 因而可以发现该 SPI。显式 `--database-type vendorx` 会跳过自动探测，适合厂商 URL 不在内置映射中的场景。

## 3. Java API：应用自己管理 DataSource

`flydb-core` 不负责下载或动态加载 JDBC 驱动。应用把厂商驱动放入自己的 Maven/Gradle 依赖，并创建 `DataSource`；Flydb 只使用这个连接池：

```java
DataSource dataSource = createVendorDataSource();

Flydb flydb = Flydb.configure()
        .dataSource(dataSource)
        // 兼容家族或自定义 SPI 建议显式指定；不写则按 JDBC URL/连接元数据探测
        .databaseType("mysql")
        .locations("classpath:db/migration")
        .load();

flydb.migrate();
```

自定义方言 JAR 放在应用运行时 classpath 后，`ServiceLoader` 会自动加载；如果应用使用了隔离类加载器，把该加载器传给 `.classLoader(yourClassLoader)`。Java API 的驱动依赖示例（坐标以厂商发布为准）：

```xml
<dependency>
  <groupId>com.vendor</groupId>
  <artifactId>vendor-jdbc</artifactId>
  <version>${vendor.jdbc.version}</version>
</dependency>
<dependency>
  <groupId>com.example.flydb</groupId>
  <artifactId>flydb-dialect-vendorx</artifactId>
  <version>${flydb.version}</version>
</dependency>
```

## 4. Spring Boot：先让 Boot 管理驱动

把厂商 JDBC 驱动（以及自定义方言 JAR，如需要）作为应用依赖，Boot 会创建主 `DataSource`。Flydb 默认复用它：

```properties
spring.datasource.url=jdbc:vendor://db.example.com:1234/app
spring.datasource.username=app_user
spring.datasource.password=${DB_PASSWORD}

flydb.locations=classpath:db/migration
# URL 是 MySQL 兼容但无法自动识别时：
flydb.database-type=mysql
```

需要使用独立 DDL 账号时，配置 `flydb.url/user/password`；这时 starter 创建独立的 `DriverManagerDataSource`。厂商 URL 无法由 JDBC 标准推断驱动类时，再设置 `flydb.driver`：

```properties
flydb.url=jdbc:vendor://db.example.com:1234/app
flydb.user=flydb_ddl
flydb.password=${FLYDB_DDL_PASSWORD}
flydb.driver=vendor.jdbc.Driver
flydb.database-type=mysql
```

应用主 `DataSource` 已经加载驱动时，不需要为了复用它重复设置 `flydb.driver`。Boot 2/3 的完整属性见[配置项参考](../reference/configuration.md)。

## 5. 接入后的最小验证清单

建议先用本地 MySQL 8 完成 CLI/脚本流程冒烟，再在目标信创或新型数据库实例上完成以下验证；MySQL 冒烟不能替代厂商契约测试：

1. `validate` 能建立连接并解析全部迁移文件；
2. `--dry-run migrate` 能完成方言选择、占位符替换和 SQL 切分，且不执行 SQL；
3. 首次运行能幂等创建历史表和锁表；
4. 两个并发 `migrate` 只有一个获得迁移锁；
5. 故意制造一条失败 DDL，确认目标库的回滚/部分提交行为与方言配置一致，并能按提示 `repair`；
6. 引号、大小写、存储过程/触发器和 schema 查询在目标版本上均通过；
7. 再执行一次 `migrate`，确认重复运行不会重复应用已成功迁移。

出现问题时按错误码定位：`FLYDB-1001` 是连接或账号问题，`FLYDB-1002` 是方言未识别/选择有歧义，`FLYDB-1003` 是驱动 JAR 或驱动类加载失败。更多错误说明见[错误码参考](../reference/errors.md)。

相关页面：[数据库上手指南索引](README.md)、[MySQL](mysql.md)、[Oracle](oracle.md)、[达梦 DM8](dm8.md)、[OceanBase-MySQL](oceanbase-mysql.md)、[OceanBase-Oracle](oceanbase-oracle.md)、[方言 SPI 设计](../design/03-dialects.md)。
