package com.flydb.core.dialect;

import java.sql.Connection;

/** OceanBase MySQL 兼容租户方言。 */
public final class OceanBaseMySQLDatabase extends MySQLFamilyDatabase {

    public OceanBaseMySQLDatabase(Connection connection) {
        super("OceanBase-MySQL", connection);
    }
}
