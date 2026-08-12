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

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) commits++;
                        if ("rollback".equals(method.getName())) rollbacks++;
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    });
        }
    }
}
