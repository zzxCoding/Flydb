package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.flydb.core.executor.SqlStatementBuilderConfig;

/**
 * MySQL 家族方言基类（设计 03 §3.2）。
 *
 * <p>家族共性：不支持 DDL 事务（隐式提交）、反引号标识符、DELIMITER 指令、反斜杠字符串转义、#{@code } 行注释。
 * 子类：MySQL / TiDB / OceanBase-MySQL。
 */
public abstract class MySQLFamilyDatabase implements Database {

    private final Connection connection;
    private final String name;

    protected MySQLFamilyDatabase(String name, Connection connection) {
        this.name = name;
        this.connection = connection;
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
        // 反引号，内部 ` 转义为 ``
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String currentSchema() throws SQLException {
        Statement stmt = connection.createStatement();
        try {
            ResultSet rs = stmt.executeQuery("SELECT DATABASE()");
            try {
                if (rs.next()) {
                    return rs.getString(1);
                }
                return null;
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
        return SqlStatementBuilderConfig.mysql();
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}