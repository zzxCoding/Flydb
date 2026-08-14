package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/** 达梦 DM8 方言类型；URL 前缀是权威，不信任兼容模式下的产品名。 */
public final class DmDatabaseType implements DatabaseType {

    @Override
    public String name() {
        return "dm";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean handlesUrl(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:dm://");
    }

    @Override
    public boolean handlesConnection(Connection connection) throws SQLException {
        String productName = connection.getMetaData().getDatabaseProductName();
        return DatabaseProbe.containsIgnoreCase(productName, "dm")
                || DatabaseProbe.containsIgnoreCase(productName, "oracle");
    }

    @Override
    public Database createDatabase(Connection connection, FlydbConfiguration configuration)
            throws SQLException {
        return new DmDatabase(connection, caseSensitive(connection));
    }

    private static boolean caseSensitive(Connection connection) throws SQLException {
        String value;
        try {
            value = DatabaseProbe.queryString(connection,
                    "SELECT SF_GET_CASE_SENSITIVE_FLAG() FROM dual");
        } catch (SQLException functionUnavailable) {
            value = DatabaseProbe.queryString(connection,
                    "SELECT PARA_VALUE FROM V$DM_INI "
                            + "WHERE PARA_NAME IN ('CASE_SENSITIVE', 'GLOBAL_STR_CASE_SENSITIVE')");
        }
        return "1".equals(value) || "Y".equalsIgnoreCase(value)
                || "TRUE".equalsIgnoreCase(value);
    }
}
