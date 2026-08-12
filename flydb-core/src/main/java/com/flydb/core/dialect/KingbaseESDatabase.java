package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.history.SchemaHistory;
import com.flydb.core.lock.MigrationLock;
import com.flydb.core.lock.TableRowLockMigrationLock;
import com.flydb.core.lock.WarningMigrationLock;

/** 人大金仓 KingbaseES 方言。 */
public final class KingbaseESDatabase extends PostgreSQLFamilyDatabase {

    public KingbaseESDatabase(Connection connection) {
        super("KingbaseES", connection);
    }

    @Override
    public MigrationLock createLock(FlydbConfiguration configuration) {
        if (supportsAdvisoryLock()) {
            return super.createLock(configuration);
        }
        try {
            MigrationLock fallback = new TableRowLockMigrationLock(
                    configuration.dataSource().getConnection(),
                    SchemaHistory.lockTableName(configuration.table()), lockOwner(),
                    configuration.lockTimeoutSeconds());
            return new WarningMigrationLock(fallback,
                    "KingbaseES 当前实例不支持 pg_advisory_lock，已降级为通用锁表方案");
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "创建 KingbaseES 降级锁连接失败: " + e.getMessage(), e);
        }
    }

    private boolean supportsAdvisoryLock() {
        PreparedStatement statement = null;
        try {
            statement = connection().prepareStatement("SELECT pg_advisory_unlock(?)");
            if (statement == null) {
                return false;
            }
            statement.setLong(1, 0L);
            statement.execute();
            return true;
        } catch (SQLException ignored) {
            return false;
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignored) {
                    // 能力探测结果已经确定。
                }
            }
        }
    }

    private static String lockOwner() {
        return System.getProperty("user.name", "unknown") + "@"
                + java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
    }
}
