package com.flydb.core.dialect;

import java.sql.Connection;

/** OceanBase Oracle 兼容租户方言（实验性）。 */
public final class OceanBaseOracleDatabase extends OracleFamilyDatabase {

    public OceanBaseOracleDatabase(Connection connection) {
        super("OceanBase-Oracle（实验性）", connection);
    }
}
