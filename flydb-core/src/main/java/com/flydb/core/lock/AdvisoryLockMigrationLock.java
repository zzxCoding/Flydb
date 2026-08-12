package com.flydb.core.lock;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.zip.CRC32;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** PostgreSQL 家族的会话级 advisory lock。 */
public final class AdvisoryLockMigrationLock implements MigrationLock {

    private final Connection connection;
    private final long lockKey;
    private final int timeoutSeconds;
    private boolean acquired;

    public AdvisoryLockMigrationLock(Connection connection, String qualifiedHistoryTable,
                                     int timeoutSeconds) {
        this.connection = connection;
        this.lockKey = lockKey(qualifiedHistoryTable);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void acquire() {
        execute("SELECT pg_advisory_lock(?)", true);
        acquired = true;
    }

    @Override
    public void release() {
        if (!acquired) {
            return;
        }
        execute("SELECT pg_advisory_unlock(?)", false);
        acquired = false;
    }

    private void execute(String sql, boolean acquiring) {
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            statement.setLong(1, lockKey);
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute();
        } catch (SQLException e) {
            ErrorCode code = acquiring
                    ? ErrorCode.LOCK_ACQUISITION_TIMEOUT
                    : ErrorCode.MIGRATION_EXECUTION_FAILED;
            throw new FlydbException(code,
                    (acquiring ? "获取" : "释放") + " advisory lock 失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(statement);
        }
    }

    static long lockKey(String qualifiedHistoryTable) {
        CRC32 crc32 = new CRC32();
        crc32.update(("flydb:" + qualifiedHistoryTable).getBytes(StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    @Override
    public void close() {
        try {
            release();
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 锁 SQL 已完成，关闭 statement 失败不改变锁结果。
            }
        }
    }
}
