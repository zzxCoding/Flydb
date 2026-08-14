package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** OceanBase 方言类型；租户兼容模式在创建 Database 时分派。 */
public final class OceanBaseDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "oceanbase";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:oceanbase://");
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        return DatabaseProbe.containsIgnoreCase(productName, "oceanbase");
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration configuration)
            throws SQLException {
        String mode = DatabaseProbe.queryString(connection,
                "SHOW VARIABLES LIKE 'ob_compatibility_mode'", 2);
        if ("mysql".equalsIgnoreCase(mode)) {
            return new OceanBaseMySQLDatabase(connection);
        }
        if ("oracle".equalsIgnoreCase(mode)) {
            return new OceanBaseOracleDatabase(connection);
        }
        throw new FlydbException(ErrorCode.UNRECOGNIZED_DATABASE_TYPE,
                "无法识别 OceanBase 租户兼容模式: " + mode);
    }
}
