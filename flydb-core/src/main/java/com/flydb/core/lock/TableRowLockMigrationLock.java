package com.flydb.core.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** MySQL/Oracle 家族使用的专用事务行锁。 */
public final class TableRowLockMigrationLock implements MigrationLock {

    private final Connection connection;
    private final String lockTable;
    private final String lockedBy;
    private final int timeoutSeconds;
    private boolean acquired;

    public TableRowLockMigrationLock(Connection connection, String lockTable,
                                     String lockedBy, int timeoutSeconds) {
        this.connection = connection;
        this.lockTable = lockTable;
        this.lockedBy = lockedBy;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void acquire() {
        try {
            connection.setAutoCommit(false);
            executeLockQuery();
            updateObservation();
            acquired = true;
        } catch (SQLException e) {
            rollbackQuietly();
            restoreAutoCommitQuietly();
            throw new FlydbException(ErrorCode.LOCK_ACQUISITION_TIMEOUT,
                    "锁表 " + lockTable + " 获取失败: " + e.getMessage()
                            + "；确认是否有另一 flydb 进程正在迁移", e);
        }
    }

    @Override
    public void release() {
        if (!acquired) {
            return;
        }
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "释放锁表事务失败: " + e.getMessage(), e);
        } finally {
            acquired = false;
            restoreAutoCommitQuietly();
        }
    }

    private void executeLockQuery() throws SQLException {
        String sql = "SELECT lock_id FROM " + lockTable
                + " WHERE lock_id = 1 FOR UPDATE";
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute();
        } finally {
            closeQuietly(statement);
        }
    }

    private void updateObservation() throws SQLException {
        String sql = "UPDATE " + lockTable
                + " SET locked_by=?, locked_at=CURRENT_TIMESTAMP WHERE lock_id=1";
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setString(1, lockedBy);
            statement.setQueryTimeout(timeoutSeconds);
            statement.executeUpdate();
        } finally {
            closeQuietly(statement);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始锁失败。
        }
    }

    private void restoreAutoCommitQuietly() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 专用连接随后会关闭。
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 不覆盖主路径异常。
            }
        }
    }

    @Override
    public void close() {
        try {
            release();
        } finally {
            closeQuietly(connection);
        }
    }
}
