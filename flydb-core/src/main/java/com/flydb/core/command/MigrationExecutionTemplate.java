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
        execute(connection, transactionalDdl, executor, recorder, new Outcome());
    }

    static void execute(Connection connection, boolean transactionalDdl,
                        MigrationExecutor executor, HistoryRecorder recorder,
                        Outcome outcome) throws SQLException {
        long started = System.nanoTime();
        outcome.reset();
        if (transactionalDdl) {
            executeTransactional(connection, executor, recorder, started, outcome);
        } else {
            executeNonTransactional(connection, executor, recorder, started, outcome);
        }
    }

    private static void executeTransactional(Connection connection,
                                             MigrationExecutor executor,
                                             HistoryRecorder recorder,
                                             long started, Outcome outcome) throws SQLException {
        outcome.phase = Phase.TRANSACTION_START;
        connection.setAutoCommit(false);
        outcome.phase = Phase.SQL_EXECUTION;
        try {
            executor.execute(connection);
            outcome.phase = Phase.HISTORY_RECORDING;
            recorder.record(true, elapsedMillis(started));
            outcome.phase = Phase.COMMIT;
            connection.commit();
            outcome.phase = Phase.COMPLETE;
        } catch (SQLException e) {
            rollback(connection, e, outcome);
            throw e;
        } catch (RuntimeException e) {
            rollback(connection, e, outcome);
            throw e;
        } finally {
            restoreAutoCommit(connection);
        }
    }

    private static void executeNonTransactional(Connection connection,
                                                MigrationExecutor executor,
                                                HistoryRecorder recorder,
                                                long started, Outcome outcome) throws SQLException {
        outcome.phase = Phase.SQL_EXECUTION;
        try {
            executor.execute(connection);
            outcome.phase = Phase.HISTORY_RECORDING;
            recorder.record(true, elapsedMillis(started));
            outcome.phase = Phase.COMPLETE;
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

    private static void rollback(Connection connection, Throwable original, Outcome outcome) {
        outcome.rollback = Rollback.ATTEMPTED;
        try {
            connection.rollback();
            outcome.rollback = Rollback.SUCCEEDED;
        } catch (SQLException rollbackFailure) {
            outcome.rollback = Rollback.FAILED;
            original.addSuppressed(rollbackFailure);
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

    static final class Outcome {
        private Phase phase;
        private Rollback rollback;

        Outcome() {
            reset();
        }

        private void reset() {
            phase = Phase.NOT_STARTED;
            rollback = Rollback.NOT_ATTEMPTED;
        }

        String failurePhaseDescription() {
            switch (phase) {
                case TRANSACTION_START: return "事务初始化";
                case SQL_EXECUTION: return "SQL 语句执行";
                case HISTORY_RECORDING: return "迁移历史记账";
                case COMMIT: return "事务提交";
                case COMPLETE: return "执行完成后的收尾";
                default: return "执行准备";
            }
        }

        String transactionResultDescription(boolean transactional) {
            if (!transactional) {
                return "未执行整体回滚；JDBC 已确认执行不等于已提交，数据库状态需人工核验";
            }
            if (phase == Phase.COMMIT) {
                if (rollback == Rollback.SUCCEEDED) {
                    return "提交结果未知；随后 JDBC rollback 返回成功也不能证明服务端未提交";
                }
                return "提交结果未知且回滚未成功，数据库状态未知";
            }
            if (rollback == Rollback.SUCCEEDED) return "已回滚（JDBC rollback 返回成功）";
            if (rollback == Rollback.FAILED) return "回滚失败，数据库状态未知";
            return "未确认回滚，数据库状态未知";
        }
    }

    private enum Phase {
        NOT_STARTED,
        TRANSACTION_START,
        SQL_EXECUTION,
        HISTORY_RECORDING,
        COMMIT,
        COMPLETE
    }

    private enum Rollback {
        NOT_ATTEMPTED,
        ATTEMPTED,
        SUCCEEDED,
        FAILED
    }
}
