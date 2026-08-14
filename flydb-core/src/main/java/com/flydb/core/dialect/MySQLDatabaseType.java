package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/**
 * MySQL 方言实现（设计 03 §4）。
 */
public final class MySQLDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:mysql://");
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        if (!"MySQL".equals(productName)) {
            return false;
        }
        try {
            return DatabaseProbe.queryString(connection, "SELECT tidb_version()") == null;
        } catch (SQLException ignored) {
            return true;
        }
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration cfg) {
        return new MySQLFamilyDatabase("MySQL", connection) {
            // MySQL 基准实现，无覆写
        };
    }
}
