package com.flydb.core.test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * flydb-core 单测用的 JDBC 桩（基于 JDK {@link Proxy}，零第三方依赖——enforcer 仅放行 JUnit/AssertJ）。
 *
 * <p>核心思路：动态代理把所有 JDBC 方法路由到 {@link InvocationHandler}，未显式桩化的方法返回
 * 类型默认值（false/0/null）。{@link #recordingConnection()} 捕获每条 {@code execute(sql)} 的 SQL 文本，
 * 供断言；{@link #failingConnection(String, RuntimeException)} 让匹配前缀的语句抛指定异常，
 * 用于验证错误携带脚本名/行号。
 */
public final class JdbcFakes {

    private JdbcFakes() {
    }

    /** 一个把每条 execute(sql) 的文本记入 captured 的连接。 */
    public static Connection recordingConnection(List<String> captured) {
        return connectionHandler((sql, stmt) -> {
            captured.add(sql);
            return false;
        }, null);
    }

    /**
     * 一个连接：其语句 execute(sql) 时，若 sql 以 {@code failPrefix} 开头则抛 {@code failure}，否则记入 captured。
     */
    public static Connection failingConnection(List<String> captured, String failPrefix,
                                               SQLException failure) {
        return connectionHandler((sql, stmt) -> {
            if (failPrefix != null && sql.startsWith(failPrefix)) {
                throw failure;
            }
            captured.add(sql);
            return false;
        }, null);
    }

    /**
     * 一个支持 JDBC batch 的连接：{@code addBatch} 缓冲语句，{@code executeBatch} 时按序应用并记入
     * captured（未走 {@code execute}，captured 非空即证明使用了 batch 路径）；命中 {@code failPrefix}
     * 时抛 {@link java.sql.BatchUpdateException}，其 updateCounts 为失败前已应用的计数（模拟遇错即停驱动）。
     */
    public static Connection batchingConnection(List<String> captured, String failPrefix) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return newBatchingStatement(captured, failPrefix);
                    }
                    if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
                        return "isClosed".equals(method.getName()) ? false : null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    /**
     * 一个模拟“遇错继续、末尾汇总”的 JDBC 驱动：整批执行完后抛异常，updateCounts 与批大小
     * 相同，并用 {@link Statement#EXECUTE_FAILED} 标记失败语句。
     */
    public static Connection continuingBatchConnection(List<String> captured, String failPrefix) {
        return continuingBatchConnection(captured, failPrefix, true);
    }

    /** 模拟抛出 BatchUpdateException 但不给 EXECUTE_FAILED 标记的非标准驱动。 */
    public static Connection unmarkedFailingBatchConnection(List<String> captured,
                                                             String failPrefix) {
        return continuingBatchConnection(captured, failPrefix, false);
    }

    private static Connection continuingBatchConnection(List<String> captured,
                                                         String failPrefix,
                                                         boolean markFailure) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return newContinuingBatchStatement(captured, failPrefix, markFailure);
                    }
                    if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
                        return "isClosed".equals(method.getName()) ? false : null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Statement newBatchingStatement(List<String> captured, String failPrefix) {
        List<String> buffer = new ArrayList<String>();
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("addBatch".equals(name) && args != null && args.length > 0) {
                        buffer.add((String) args[0]);
                        return null;
                    }
                    if ("executeBatch".equals(name)) {
                        int applied = 0;
                        for (String sql : new ArrayList<String>(buffer)) {
                            if (failPrefix != null && sql.startsWith(failPrefix)) {
                                throw new java.sql.BatchUpdateException("synthetic batch failure",
                                        appliedCounts(applied));
                            }
                            captured.add(sql);
                            applied++;
                        }
                        buffer.clear();
                        return new int[applied];
                    }
                    if ("clearBatch".equals(name)) {
                        buffer.clear();
                        return null;
                    }
                    if ("close".equals(name) || "isClosed".equals(name)) {
                        return "isClosed".equals(name) ? false : null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Statement newContinuingBatchStatement(List<String> captured,
                                                          String failPrefix,
                                                          boolean markFailure) {
        List<String> buffer = new ArrayList<String>();
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("addBatch".equals(name) && args != null && args.length > 0) {
                        buffer.add((String) args[0]);
                        return null;
                    }
                    if ("executeBatch".equals(name)) {
                        int[] counts = new int[buffer.size()];
                        boolean failed = false;
                        for (int i = 0; i < buffer.size(); i++) {
                            String sql = buffer.get(i);
                            if (failPrefix != null && sql.startsWith(failPrefix)) {
                                counts[i] = markFailure ? Statement.EXECUTE_FAILED : 1;
                                failed = true;
                            } else {
                                captured.add(sql);
                                counts[i] = 1;
                            }
                        }
                        if (failed) {
                            throw new java.sql.BatchUpdateException(
                                    "synthetic continuing batch failure", counts);
                        }
                        buffer.clear();
                        return counts;
                    }
                    if ("clearBatch".equals(name)) {
                        buffer.clear();
                        return null;
                    }
                    if ("close".equals(name) || "isClosed".equals(name)) {
                        return "isClosed".equals(name) ? false : null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static int[] appliedCounts(int applied) {
        int[] counts = new int[applied];
        for (int i = 0; i < applied; i++) {
            counts[i] = 1;
        }
        return counts;
    }

    private interface StatementBehavior {
        boolean execute(String sql, Statement stmt) throws SQLException;
    }

    private static Connection connectionHandler(StatementBehavior behavior, Object self) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new RecordingHandler(behavior));
    }

    private static class RecordingHandler implements InvocationHandler {
        private final StatementBehavior behavior;
        private Statement cachedStatement;

        RecordingHandler(StatementBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("createStatement".equals(name) || "createStatement".equals(name)) {
                if (cachedStatement == null) {
                    cachedStatement = newStatement(behavior);
                }
                return cachedStatement;
            }
            if ("close".equals(name) || "isClosed".equals(name)) {
                return "isClosed".equals(name) ? false : null;
            }
            return defaultValue(method.getReturnType());
        }

        private static Statement newStatement(StatementBehavior behavior) {
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        if ("execute".equals(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof String) {
                            return behavior.execute((String) args[0], (Statement) proxy);
                        }
                        if ("close".equals(method.getName()) || "isClosed".equals(method.getName())) {
                            return "isClosed".equals(method.getName()) ? false : null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    /** 返回 JVM 默认值（boolean→false、数值→0、引用→null），用于未桩化的 JDBC 方法。 */
    public static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    /** 便捷：返回一个新的可变列表（调用方常用）。 */
    public static List<String> newCapture() {
        return new ArrayList<String>();
    }
}
