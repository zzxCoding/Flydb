package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/** 人大金仓 KingbaseES 方言类型。 */
public final class KingbaseESDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "kingbasees";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:kingbase8://");
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        if (DatabaseProbe.containsIgnoreCase(productName, "kingbase")) {
            return true;
        }
        return DatabaseProbe.containsIgnoreCase(
                DatabaseProbe.queryString(connection, "SELECT version()"), "kingbase");
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration configuration) {
        return new KingbaseESDatabase(connection);
    }
}
