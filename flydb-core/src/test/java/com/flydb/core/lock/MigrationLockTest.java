package com.flydb.core.lock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MigrationLock")
class MigrationLockTest {

    @Test
    @DisplayName("PG advisory lock 使用稳定 key 获取并释放会话锁")
    void advisoryLockAcquiresAndReleasesStableKey() {
        RecordingJdbc jdbc = new RecordingJdbc(false);
        MigrationLock lock = new AdvisoryLockMigrationLock(jdbc.connection(),
                "public.flydb_schema_history", 12);

        lock.acquire();
        lock.release();

        assertThat(jdbc.sql).containsExactly(
                "SELECT pg_advisory_lock(?)",
                "SELECT pg_advisory_unlock(?)");
        assertThat(jdbc.longParameters).hasSize(2);
        assertThat(jdbc.longParameters.get(0)).isEqualTo(jdbc.longParameters.get(1));
        assertThat(jdbc.queryTimeouts).containsExactly(12, 12);
    }

    @Test
    @DisplayName("锁等待 SQL 异常映射为 FLYDB-3001")
    void lockFailureMapsToStableErrorCode() {
        RecordingJdbc jdbc = new RecordingJdbc(true);
        MigrationLock lock = new AdvisoryLockMigrationLock(jdbc.connection(),
                "public.flydb_schema_history", 3);

        assertThatThrownBy(lock::acquire)
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.LOCK_ACQUISITION_TIMEOUT);
    }

    @Test
    @DisplayName("通用锁表锁用独立事务持有行锁并在释放时提交")
    void tableRowLockOwnsDedicatedTransaction() {
        RecordingJdbc jdbc = new RecordingJdbc(false);
        MigrationLock lock = new TableRowLockMigrationLock(jdbc.connection(),
                "flydb_schema_history_lock", "host/42", 9);

        lock.acquire();
        lock.release();

        assertThat(jdbc.autoCommitValues).containsExactly(false, true);
        assertThat(jdbc.sql).containsExactly(
                "SELECT lock_id FROM flydb_schema_history_lock WHERE lock_id = 1 FOR UPDATE",
                "UPDATE flydb_schema_history_lock SET locked_by=?, locked_at=CURRENT_TIMESTAMP WHERE lock_id=1");
        assertThat(jdbc.stringParameters).containsExactly("host/42");
        assertThat(jdbc.commits).isEqualTo(1);
    }

    private static final class RecordingJdbc implements InvocationHandler {
        private final boolean failExecute;
        private final List<String> sql = new ArrayList<String>();
        private final List<Long> longParameters = new ArrayList<Long>();
        private final List<Integer> queryTimeouts = new ArrayList<Integer>();
        private final List<Boolean> autoCommitValues = new ArrayList<Boolean>();
        private final List<String> stringParameters = new ArrayList<String>();
        private int commits;

        RecordingJdbc(boolean failExecute) {
            this.failExecute = failExecute;
        }

        Connection connection() {
            return proxy(Connection.class, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("prepareStatement".equals(method.getName())) {
                sql.add((String) args[0]);
                return proxy(PreparedStatement.class, new StatementHandler());
            }
            if ("setAutoCommit".equals(method.getName())) {
                autoCommitValues.add((Boolean) args[0]);
                return null;
            }
            if ("commit".equals(method.getName())) {
                commits++;
                return null;
            }
            if ("close".equals(method.getName())) {
                return null;
            }
            return defaultValue(method.getReturnType());
        }

        private final class StatementHandler implements InvocationHandler {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("setLong".equals(method.getName())) {
                    longParameters.add((Long) args[1]);
                } else if ("setString".equals(method.getName())) {
                    stringParameters.add((String) args[1]);
                } else if ("setQueryTimeout".equals(method.getName())) {
                    queryTimeouts.add((Integer) args[0]);
                } else if ("execute".equals(method.getName()) && failExecute) {
                    throw new SQLException("timeout");
                }
                return defaultValue(method.getReturnType());
            }
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                    new Class<?>[]{type}, handler));
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}
