package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.flydb.core.executor.SqlStatementBuilderConfig;

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
    public void close() throws Exception {
        connection.close();
    }
}