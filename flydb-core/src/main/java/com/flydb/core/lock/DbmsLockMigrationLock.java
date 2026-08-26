package com.flydb.core.lock;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 可选的 DBMS_LOCK 会话锁实现；OceanBase-Oracle 当前默认使用锁表行锁。 */
public final class DbmsLockMigrationLock implements MigrationLock {

    private static final int SUCCESS = 0;
    private static final int ALREADY_OWNED = 4;

    private final Connection connection;
    private final String lockName;
    private final int timeoutSeconds;
    private String lockHandle;
    private boolean acquired;

    public DbmsLockMigrationLock(Connection connection, String lockName, int timeoutSeconds) {
        this.connection = connection;
        this.lockName = lockName;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void acquire() {
        try {
            allocateUnique();
            int result = request();
            if (result != SUCCESS && result != ALREADY_OWNED) {
                throw new FlydbException(ErrorCode.LOCK_ACQUISITION_TIMEOUT,
                        "DBMS_LOCK.REQUEST 获取失败，返回码: " + result);
            }
            acquired = true;
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.LOCK_ACQUISITION_TIMEOUT,
                    "获取 OceanBase DBMS_LOCK 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void release() {
        if (!acquired) {
            return;
        }
        try {
            CallableStatement statement = connection.prepareCall(
                    "BEGIN ? := DBMS_LOCK.RELEASE(?); END;");
            try {
                statement.registerOutParameter(1, Types.INTEGER);
                statement.setString(2, lockHandle);
                statement.execute();
                int result = statement.getInt(1);
                if (result != SUCCESS) {
                    throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                            "DBMS_LOCK.RELEASE 失败，返回码: " + result);
                }
            } finally {
                statement.close();
            }
            acquired = false;
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "释放 OceanBase DBMS_LOCK 失败: " + e.getMessage(), e);
        }
    }

    private void allocateUnique() throws SQLException {
        CallableStatement statement = connection.prepareCall(
                "BEGIN DBMS_LOCK.ALLOCATE_UNIQUE(lockname => ?, lockhandle => ?); END;");
        try {
            statement.setString(1, lockName);
            statement.registerOutParameter(2, Types.VARCHAR);
            statement.execute();
            lockHandle = statement.getString(2);
        } finally {
            statement.close();
        }
    }

    private int request() throws SQLException {
        CallableStatement statement = connection.prepareCall(
                "BEGIN ? := DBMS_LOCK.REQUEST(lockhandle => ?, "
                        + "lockmode => DBMS_LOCK.X_MODE, timeout => ?, "
                        + "release_on_commit => FALSE); END;");
        try {
            statement.registerOutParameter(1, Types.INTEGER);
            statement.setString(2, lockHandle);
            statement.setInt(3, timeoutSeconds);
            statement.execute();
            return statement.getInt(1);
        } finally {
            statement.close();
        }
    }

    @Override
    public void close() {
        try {
            release();
        } finally {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 专用锁连接关闭失败不覆盖命令结果。
            }
        }
    }
}
