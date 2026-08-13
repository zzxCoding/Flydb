package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/** Oracle 官方 JDBC 方言类型；使用 Oracle 家族的事务外 DDL 与 PL/SQL 语义。 */
public final class OracleDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "oracle";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:oracle:");
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        return DatabaseProbe.containsIgnoreCase(
                connection.getMetaData().getDatabaseProductName(), "oracle");
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration configuration) {
        return new OracleFamilyDatabase("Oracle", connection) {
            // Oracle 官方实现使用 Oracle 家族默认的历史表、锁表和 PL/SQL 语义。
        };
    }
}
