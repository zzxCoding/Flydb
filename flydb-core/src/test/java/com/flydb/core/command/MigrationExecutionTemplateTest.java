package com.flydb.core.command;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MigrationExecutionTemplate")
class MigrationExecutionTemplateTest {

    @Test
    @DisplayName("支持 DDL 事务时失败整体回滚且历史表无痕")
    void transactionalFailureRollsBackWithoutHistory() {
        JdbcState state = new JdbcState();
        HistoryState history = new HistoryState();

        assertThatThrownBy(() -> MigrationExecutionTemplate.execute(state.connection(), true,
                connection -> { throw new SQLException("broken"); }, history::record))
                .isInstanceOf(SQLException.class);

        assertThat(state.rollbacks).isEqualTo(1);
        assertThat(state.commits).isZero();
        assertThat(history.calls).isZero();
    }

    @Test
    @DisplayName("不支持 DDL 事务时失败写入 success=false")
    void nonTransactionalFailureRecordsFailedHistory() {
        JdbcState state = new JdbcState();
        HistoryState history = new HistoryState();

        assertThatThrownBy(() -> MigrationExecutionTemplate.execute(state.connection(), false,
                connection -> { throw new SQLException("broken"); }, history::record))
                .isInstanceOf(SQLException.class);

        assertThat(history.calls).isEqualTo(1);
        assertThat(history.success).isFalse();
        assertThat(state.rollbacks).isZero();
    }

    @Test
    @DisplayName("成功迁移与历史记录同事务提交")
    void successCommitsMigrationAndHistoryTogether() throws Exception {
        JdbcState state = new JdbcState();
        HistoryState history = new HistoryState();

        MigrationExecutionTemplate.execute(state.connection(), true,
                connection -> { }, history::record);

        assertThat(history.success).isTrue();
        assertThat(state.commits).isEqualTo(1);
    }

    @Test
    @DisplayName("回滚失败被保留为状态未知且不覆盖原始执行异常")
    void rollbackFailureKeepsOriginalAndMarksOutcomeUnknown() {
        JdbcState state = new JdbcState();
        state.rollbackFailure = new SQLException("rollback connection lost");
        HistoryState history = new HistoryState();
        MigrationExecutionTemplate.Outcome outcome = new MigrationExecutionTemplate.Outcome();

        assertThatThrownBy(() -> MigrationExecutionTemplate.execute(state.connection(), true,
                connection -> { throw new SQLException("statement broken"); },
                history::record, outcome))
                .isInstanceOf(SQLException.class)
                .hasMessage("statement broken")
                .satisfies(error -> assertThat(error.getSuppressed()).singleElement()
                        .satisfies(suppressed -> assertThat(suppressed.getMessage())
                                .isEqualTo("rollback connection lost")));

        assertThat(outcome.failurePhaseDescription()).isEqualTo("SQL 语句执行");
        assertThat(outcome.transactionResultDescription(true))
                .isEqualTo("回滚失败，数据库状态未知");
    }

    @Test
    @DisplayName("提交失败后不能因 rollback 返回成功而宣称事务已回滚")
    void commitFailureRemainsUnknownAfterRollbackReturns() {
        JdbcState state = new JdbcState();
        state.commitFailure = new SQLException("commit response lost");
        HistoryState history = new HistoryState();
        MigrationExecutionTemplate.Outcome outcome = new MigrationExecutionTemplate.Outcome();

        assertThatThrownBy(() -> MigrationExecutionTemplate.execute(state.connection(), true,
                connection -> { }, history::record, outcome))
                .isInstanceOf(SQLException.class)
                .hasMessage("commit response lost");

        assertThat(outcome.failurePhaseDescription()).isEqualTo("事务提交");
        assertThat(outcome.transactionResultDescription(true))
                .isEqualTo("提交结果未知；随后 JDBC rollback 返回成功也不能证明服务端未提交");
    }

    private static final class HistoryState {
        private int calls;
        private boolean success;

        void record(boolean success, int elapsedMillis) {
            calls++;
            this.success = success;
        }
    }

    private static final class JdbcState {
        private int commits;
        private int rollbacks;
        private SQLException commitFailure;
        private SQLException rollbackFailure;

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            commits++;
                            if (commitFailure != null) throw commitFailure;
                        }
                        if ("rollback".equals(method.getName())) {
                            rollbacks++;
                            if (rollbackFailure != null) throw rollbackFailure;
                        }
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    });
        }
    }
}
