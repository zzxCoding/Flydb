package com.flydb.core.command;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.time.Instant;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.dialect.Database;
import com.flydb.core.dialect.DatabaseType;
import com.flydb.core.dialect.DatabaseTypeRegistry;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.history.SchemaHistory;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.ResolvedMigration;
import com.flydb.core.resolver.ResolverContext;
import com.flydb.core.resolver.SqlMigrationResolver;

/** 命令共用的连接、方言、历史仓储与解析器运行时。 */
final class CommandRuntime implements AutoCloseable {

    private final FlydbConfiguration configuration;
    private final Connection connection;
    private final Database database;
    private final SchemaHistory history;
    private final List<ResolvedMigration> resolved;

    private CommandRuntime(FlydbConfiguration configuration, Connection connection,
                           Database database, SchemaHistory history,
                           List<ResolvedMigration> resolved) {
        this.configuration = configuration;
        this.connection = connection;
        this.database = database;
        this.history = history;
        this.resolved = resolved;
    }

    static CommandRuntime open(FlydbConfiguration configuration, boolean ensureHistory) {
        if (configuration.dataSource() == null) {
            throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                    "URL 模式的 DriverDataSource 将由阶段 6 动态驱动加载器提供；当前请传入 DataSource");
        }
        try {
            Connection connection = configuration.dataSource().getConnection();
            if (connection == null) {
                throw new SQLException("DataSource 返回 null Connection");
            }
            String url = configuration.url() != null
                    ? configuration.url() : connection.getMetaData().getURL();
            DatabaseType type = new DatabaseTypeRegistry().detect(url, connection,
                    configuration.databaseType());
            Database database = type.createDatabase(connection, configuration);
            SchemaHistory history = new SchemaHistory(configuration.table(),
                    database.schemaHistoryDdl(), connection);
            if (ensureHistory) {
                history.ensureExists();
            }
            List<ResolvedMigration> resolved = new SqlMigrationResolver()
                    .resolveMigrations(new DefaultResolverContext(configuration));
            return new CommandRuntime(configuration, connection, database, history, resolved);
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "初始化命令运行时失败: " + e.getMessage(), e);
        }
    }

    FlydbConfiguration configuration() { return configuration; }
    Connection connection() { return connection; }
    Database database() { return database; }
    SchemaHistory history() { return history; }
    List<ResolvedMigration> resolved() { return resolved; }
    List<AppliedMigration> applied() { return history.findAll(); }

    Map<String, String> builtIns() {
        return builtIns(database, configuration);
    }

    static Map<String, String> builtIns(Database database, FlydbConfiguration configuration) {
        Map<String, String> values = new HashMap<String, String>();
        try {
            values.put("flydb:database", database.name());
            values.put("flydb:schema", nullToEmpty(database.currentSchema()));
            values.put("flydb:user", nullToEmpty(database.currentUser()));
            values.put("flydb:table", configuration.table());
            values.put("flydb:timestamp", Instant.now().toString());
            return values;
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "读取占位符内置变量失败: " + e.getMessage(), e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        try {
            database.close();
        } catch (Exception ignored) {
            // 命令结果或主异常优先。
        }
    }

    private static final class DefaultResolverContext implements ResolverContext {
        private final FlydbConfiguration configuration;

        DefaultResolverContext(FlydbConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override public List<String> locations() { return configuration.locations(); }
        @Override public Charset encoding() { return configuration.encoding(); }
        @Override public String sqlMigrationPrefix() { return configuration.sqlMigrationPrefix(); }
        @Override public String repeatableMigrationPrefix() { return configuration.repeatableMigrationPrefix(); }
        @Override public String undoMigrationPrefix() { return configuration.undoMigrationPrefix(); }
        @Override public String sqlMigrationSeparator() { return configuration.sqlMigrationSeparator(); }
        @Override public String sqlMigrationSuffix() { return configuration.sqlMigrationSuffix(); }
        @Override public ClassLoader classLoader() { return configuration.classLoader(); }
    }
}
