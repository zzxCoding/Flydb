package com.flydb.core.command;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.executor.MigrationExecutor;

/** 单份迁移的事务/失败记账模板（设计 04 §3）。 */
final class MigrationExecutionTemplate {

    private MigrationExecutionTemplate() {
    }

    static void execute(Connection connection, boolean transactionalDdl,
                        MigrationExecutor executor, HistoryRecorder recorder) throws SQLException {
        long started = System.nanoTime();
        if (transactionalDdl) {
            executeTransactional(connection, executor, recorder, started);
        } else {
            executeNonTransactional(connection, executor, recorder, started);
        }
    }

    private static void executeTransactional(Connection connection,
                                             MigrationExecutor executor,
                                             HistoryRecorder recorder,
                                             long started) throws SQLException {
        connection.setAutoCommit(false);
        try {
            executor.execute(connection);
            recorder.record(true, elapsedMillis(started));
            connection.commit();
        } catch (SQLException e) {
            rollbackQuietly(connection);
            throw e;
        } catch (RuntimeException e) {
            rollbackQuietly(connection);
            throw e;
        } finally {
            restoreAutoCommit(connection);
        }
    }

    private static void executeNonTransactional(Connection connection,
                                                MigrationExecutor executor,
                                                HistoryRecorder recorder,
                                                long started) throws SQLException {
        try {
            executor.execute(connection);
            recorder.record(true, elapsedMillis(started));
        } catch (SQLException e) {
            recordFailure(recorder, started, e);
            throw e;
        } catch (RuntimeException e) {
            recordFailure(recorder, started, e);
            throw e;
        }
    }

    private static void recordFailure(HistoryRecorder recorder, long started,
                                      RuntimeException original) {
        try {
            recorder.record(false, elapsedMillis(started));
        } catch (RuntimeException historyFailure) {
            original.addSuppressed(historyFailure);
        }
    }

    private static void recordFailure(HistoryRecorder recorder, long started,
                                      SQLException original) {
        try {
            recorder.record(false, elapsedMillis(started));
        } catch (RuntimeException historyFailure) {
            original.addSuppressed(historyFailure);
        }
    }

    private static int elapsedMillis(long started) {
        long millis = (System.nanoTime() - started) / 1_000_000L;
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始执行异常。
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
            // 连接随后由命令上下文关闭。
        }
    }

    interface HistoryRecorder {
        void record(boolean success, int elapsedMillis);
    }
}
