package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/**
 * PostgreSQL 方言实现（设计 03 §4）。
 */
public final class PostgreSQLDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql://");
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        return "PostgreSQL".equals(productName);
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration cfg) {
        return new PostgreSQLFamilyDatabase("PostgreSQL", connection) {
            // PostgreSQL 基准实现，无覆写
        };
    }
}