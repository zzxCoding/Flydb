package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/** openGauss 方言类型。 */
public final class OpenGaussDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "opengauss";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && (jdbcUrl.startsWith("jdbc:opengauss://")
                || jdbcUrl.startsWith("jdbc:postgresql://"));
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        return DatabaseProbe.containsIgnoreCase(
                DatabaseProbe.queryString(connection, "SELECT version()"), "opengauss");
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration configuration) {
        return new PostgreSQLFamilyDatabase("openGauss", connection) {
            // openGauss 保留 PG 家族的事务、DDL、解析和 advisory lock 语义。
        };
    }
}
