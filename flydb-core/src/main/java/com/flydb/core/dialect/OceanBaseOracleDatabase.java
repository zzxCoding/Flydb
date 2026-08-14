package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.lock.DbmsLockMigrationLock;
import com.flydb.core.lock.MigrationLock;

/** OceanBase Oracle 兼容租户方言（实验性）。 */
public final class OceanBaseOracleDatabase extends OracleFamilyDatabase {

    public OceanBaseOracleDatabase(Connection connection) {
        super("OceanBase-Oracle（实验性）", connection);
    }

    @Override
    public MigrationLock createLock(FlydbConfiguration configuration) {
        try {
            return new DbmsLockMigrationLock(configuration.dataSource().getConnection(),
                    "flydb:" + configuration.table(), configuration.lockTimeoutSeconds());
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "创建 OceanBase DBMS_LOCK 连接失败: " + e.getMessage(), e);
        }
    }
}
