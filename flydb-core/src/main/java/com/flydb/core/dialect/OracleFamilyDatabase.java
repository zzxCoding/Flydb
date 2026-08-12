package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.executor.SqlStatementBuilderConfig;
import com.flydb.core.history.SchemaHistory;
import com.flydb.core.history.SchemaHistoryDdl;
import com.flydb.core.lock.MigrationLock;
import com.flydb.core.lock.TableRowLockMigrationLock;

/** Oracle 兼容家族基类（达梦 DM8 / OceanBase-Oracle）。 */
public abstract class OracleFamilyDatabase implements Database {

    private final String name;
    private final Connection connection;

    protected OracleFamilyDatabase(String name, Connection connection) {
        this.name = name;
        this.connection = connection;
    }

    protected final Connection connection() {
        return connection;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supportsDdlTransactions() {
        return false;
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String currentSchema() throws SQLException {
        Statement statement = connection.createStatement();
        try {
            ResultSet resultSet = statement.executeQuery(
                    "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual");
            try {
                return resultSet.next() ? resultSet.getString(1) : null;
            } finally {
                resultSet.close();
            }
        } finally {
            statement.close();
        }
    }

    @Override
    public String currentUser() throws SQLException {
        return connection.getMetaData().getUserName();
    }

    @Override
    public SqlStatementBuilderConfig statementBuilderConfig() {
        return SqlStatementBuilderConfig.oracle();
    }

    @Override
    public SchemaHistoryDdl schemaHistoryDdl() {
        return SchemaHistoryDdl.oracle();
    }

    @Override
    public MigrationLock createLock(FlydbConfiguration configuration) {
        try {
            return new TableRowLockMigrationLock(configuration.dataSource().getConnection(),
                    lockTableName(configuration), lockOwner(),
                    configuration.lockTimeoutSeconds());
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "创建 Oracle 兼容锁连接失败: " + e.getMessage(), e);
        }
    }

    /** 允许大小写敏感的 Oracle 兼容方言覆写锁表标识符。 */
    protected String lockTableName(FlydbConfiguration configuration) {
        return SchemaHistory.lockTableName(configuration.table());
    }

    @Override
    public CleanStrategy cleanStrategy() {
        return new MetadataCleanStrategy('"', false, false, false);
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }

    private static String lockOwner() {
        return System.getProperty("user.name", "unknown") + "@"
                + java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    }
}
