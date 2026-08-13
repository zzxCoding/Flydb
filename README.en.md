English | [中文](./README.md)

# Flydb

Flydb is a versioned schema migration tool for databases with JDBC drivers. It provides built-in dialects for mainstream databases, treats Chinese Xinchuang databases as first-class targets, and supports niche JDBC databases through the `DatabaseType` SPI.

Flydb targets Java 8. `flydb-core` has no third-party runtime dependencies, while the standalone CLI loads JDBC drivers dynamically from `drivers/` instead of bundling vendor drivers.

> Flydb 2.0 is being delivered in stages. The current codebase includes the core engine, CLI, built-in mainstream and Xinchuang dialects, and Spring Boot 2/3 starters. Database support is reported by verification level; an implemented dialect is not presented as production certification.

See the [database getting-started guides](./docs/getting-started/README.md) for driver, URL, permission, and limitation details. Verification level is evidence, not vendor certification.

## Quick start

Prerequisites: Java 8 or newer, an existing target database, and a Java 8-compatible JDBC driver.

```bash
unzip flydb-cli-2.0.0.zip
cd flydb-cli-2.0.0
cp /path/to/mysql-connector-j.jar drivers/

bin/flydb init \
  --url 'jdbc:mysql://127.0.0.1:3306/demo' \
  --user flydb_user \
  --database-type mysql \
  --yes

export FLYDB_PASSWORD='replace-me'
bin/flydb --dry-run migrate
bin/flydb migrate
bin/flydb info
bin/flydb validate
```

The generated files are `flydb.conf`, `db/migration/V1__init.sql`, and `drivers/README.md`. Existing files are never overwritten.

## Migration names

```text
V1__create_user.sql       versioned migration
V1.1__add_status.sql      dotted version
R__refresh_user_view.sql  repeatable when its checksum changes
U1__create_user.sql       undo for V1
```

> **2.0 naming change:** `R<version>__...sql` is rejected with `FLYDB-2005`. Use `U<version>__...sql` for undo scripts and versionless `R__...sql` for repeatable migrations; the check cannot be disabled.

Configuration precedence is `CLI > FLYDB_* environment > flydb.conf > defaults`. Passwords can be supplied through `FLYDB_PASSWORD`, `${env:DB_PASSWORD}`, or `flydb.password.file`.

Commands: `migrate`, `info`, `validate`, `baseline`, `repair`, `clean`, `undo`, `init`, and `version`. `--dry-run` is supported by migrate and undo.

Exit codes: `0` success, `1` general error, `2` validation failure, `3` lock failure, `4` configuration error, and `5` user interruption.

See the [design overview](./docs/design/00-overview.md), [CLI and configuration reference](./docs/design/06-config-cli.md), [configuration reference](./docs/reference/configuration.md), [error-code reference](./docs/reference/errors.md), and [implementation plan](./docs/design/09-implementation-plan.md).

## Java API

```java
Flydb flydb = Flydb.configure()
    .dataSource(dataSource)
    .locations("classpath:db/migration")
    .load();
flydb.migrate();
```

## Spring Boot

Choose one starter for the application's runtime. It runs `migrate` while the Spring context is being initialized and aborts startup if migration fails.

```xml
<!-- Spring Boot 3.x / Java 17+ -->
<dependency>
  <groupId>com.flydb</groupId>
  <artifactId>flydb-spring-boot-3-starter</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

Java 8 applications use `flydb-spring-boot-2-starter`, built for Spring Boot 2.7.18. [Spring Boot 2.7.18 ended open-source support](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/) for the Boot 2.x line, so the Boot 2 starter is intended for legacy systems that cannot upgrade yet; new applications should prefer the Boot 3 starter.

Flydb reuses the application's primary `DataSource` by default:

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/demo
spring.datasource.username=app_user
spring.datasource.password=${DB_PASSWORD}

flydb.locations=classpath:db/migration
flydb.database-type=mysql
```

Set `flydb.url`, `flydb.user`, and `flydb.password` to migrate with a separate DDL account while the application keeps its lower-privilege `DataSource`. Set `flydb.enabled=false` to disable auto-configuration completely. See the runnable [Boot 2](./examples/boot2-demo) and [Boot 3](./examples/boot3-demo) examples.

## Build

The full reactor, including the Boot 3 modules, is built with Java 17:

```bash
jdk17
mvn verify
```

The Boot 2 starter, Boot 2 example, core, and CLI retain Java 8 bytecode. The CLI distribution is generated at `flydb-cli/target/flydb-cli-2.0.0-SNAPSHOT.zip`.

The local integration contract defaults to MySQL 8. The CI workflow selects one family at a time with `-Dflydb.integration.database=<dialect>`; PostgreSQL is opt-in locally.

## License

[MIT](./LICENSE). Users obtain JDBC drivers separately and must comply with vendor licensing and distribution terms.
