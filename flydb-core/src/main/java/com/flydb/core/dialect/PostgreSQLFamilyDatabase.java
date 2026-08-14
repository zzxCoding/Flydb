package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.flydb.core.executor.SqlStatementBuilderConfig;
import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.history.SchemaHistory;
import com.flydb.core.history.SchemaHistoryDdl;
import com.flydb.core.lock.AdvisoryLockMigrationLock;
import com.flydb.core.lock.MigrationLock;

/**
 * PostgreSQL 家族方言基类（设计 03 §3.1）。
 *
 * <p>家族共性：支持 DDL 事务（failure 自愈）、双引号标识符、dollar-quoting。
 * 子类：PostgreSQL / KingbaseES / openGauss。
 */
public abstract class PostgreSQLFamilyDatabase implements Database {

    private final Connection connection;
    private final String name;

    protected PostgreSQLFamilyDatabase(String name, Connection connection) {
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
        return true;
    }

    @Override
    public String quote(String identifier) {
        // 双引号，内部 " 转义为 ""
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String currentSchema() throws SQLException {
        Statement stmt = connection.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT current_schema()");
            if (rs == null) {
                return "public";
            }
            try {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return "public";
            } finally {
                rs.close();
            }
        } finally {
            stmt.close();
        }
    }

    @Override
    public String currentUser() throws SQLException {
        return connection.getMetaData().getUserName();
    }

    @Override
    public SqlStatementBuilderConfig statementBuilderConfig() {
        return SqlStatementBuilderConfig.postgresql();
    }

    @Override
    public SchemaHistoryDdl schemaHistoryDdl() {
        return SchemaHistoryDdl.postgresql();
    }

    @Override
    public MigrationLock createLock(FlydbConfiguration configuration) {
        try {
            Connection lockConnection = configuration.dataSource().getConnection();
            String schema = currentSchema();
            String qualifiedTable = schema == null || schema.isEmpty()
                    ? configuration.table() : schema + "." + configuration.table();
            return new AdvisoryLockMigrationLock(lockConnection, qualifiedTable,
                    configuration.lockTimeoutSeconds());
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "创建 PostgreSQL 锁连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public CleanStrategy cleanStrategy() {
        return new MetadataCleanStrategy('"', true, false, true);
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
