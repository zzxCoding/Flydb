package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/** TiDB 方言类型（MySQL 协议，通过 tidb_version() 消歧）。 */
public final class TiDBDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "tidb";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:mysql://");
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        return DatabaseProbe.queryString(connection, "SELECT tidb_version()") != null;
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration configuration) {
        return new MySQLFamilyDatabase("TiDB", connection) {
            // 锁表与 MySQL 兼容；异步在线 DDL 的耗时语义由集成测试记录。
        };
    }
}
