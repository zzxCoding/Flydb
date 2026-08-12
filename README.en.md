English | [中文](./README.md)

# Flydb

Flydb is a versioned schema migration tool for databases with JDBC drivers. It provides built-in dialects for mainstream databases, treats Chinese Xinchuang databases as first-class targets, and supports niche JDBC databases through the `DatabaseType` SPI.

Flydb targets Java 8. `flydb-core` has no third-party runtime dependencies, while the standalone CLI loads JDBC drivers dynamically from `drivers/` instead of bundling vendor drivers.

> Flydb 2.0 is being delivered in stages. Database support is reported by verification level; an implemented dialect is not presented as production certification.

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

Configuration precedence is `CLI > FLYDB_* environment > flydb.conf > defaults`. Passwords can be supplied through `FLYDB_PASSWORD`, `${env:DB_PASSWORD}`, or `flydb.password.file`.

Commands: `migrate`, `info`, `validate`, `baseline`, `repair`, `clean`, `undo`, `init`, and `version`. `--dry-run` is supported by migrate and undo.

Exit codes: `0` success, `1` general error, `2` validation failure, `3` lock failure, `4` configuration error, and `5` user interruption.

See the [design overview](./docs/design/00-overview.md), [CLI and configuration reference](./docs/design/06-config-cli.md), and [implementation plan](./docs/design/09-implementation-plan.md).

## Java API

```java
Flydb flydb = Flydb.configure()
    .dataSource(dataSource)
    .locations("classpath:db/migration")
    .load();
flydb.migrate();
```

## Build

```bash
mvn verify
```

The CLI distribution is generated at `flydb-cli/target/flydb-cli-2.0.0-SNAPSHOT.zip`.

## License

[MIT](./LICENSE). Users obtain JDBC drivers separately and must comply with vendor licensing and distribution terms.
