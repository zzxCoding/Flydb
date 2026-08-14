package com.flydb.core.command;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.BatchUpdateException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

/** 命令契约测试用的最小内存 JDBC 实现。 */
final class InMemoryFlydbDataSource implements DataSource {

    private final boolean postgres;
    private final List<Map<String, Object>> history = new ArrayList<Map<String, Object>>();
    private final List<String> executedSql = new ArrayList<String>();
    private int commits;
    private int rollbacks;
    private int batches;

    InMemoryFlydbDataSource(boolean postgres) {
        this.postgres = postgres;
    }

    List<Map<String, Object>> history() { return history; }
    List<String> executedSql() { return executedSql; }
    int commits() { return commits; }
    int rollbacks() { return rollbacks; }
    int batches() { return batches; }

    @Override
    public Connection getConnection() {
        return proxy(Connection.class, new ConnectionHandler());
    }

    @Override public Connection getConnection(String username, String password) { return getConnection(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    @Override public <T> T unwrap(Class<T> iface) { return null; }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }

    private final class ConnectionHandler implements InvocationHandler {
        private boolean autoCommit = true;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("getMetaData".equals(name)) return metadata();
            if ("createStatement".equals(name)) return statement();
            if ("prepareStatement".equals(name)) return prepared((String) args[0]);
            if ("setAutoCommit".equals(name)) { autoCommit = (Boolean) args[0]; return null; }
            if ("getAutoCommit".equals(name)) return autoCommit;
            if ("commit".equals(name)) { commits++; return null; }
            if ("rollback".equals(name)) { rollbacks++; return null; }
            if ("isClosed".equals(name)) return false;
            return defaultValue(method.getReturnType());
        }
    }

    private DatabaseMetaData metadata() {
        return proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if ("getURL".equals(method.getName())) {
                return postgres ? "jdbc:postgresql://localhost/test" : "jdbc:mysql://localhost/test";
            }
            if ("getDatabaseProductName".equals(method.getName())) {
                return postgres ? "PostgreSQL" : "MySQL";
            }
            if ("getUserName".equals(method.getName())) return "tester";
            if ("getTables".equals(method.getName())) return tableRows(args);
            return defaultValue(method.getReturnType());
        });
    }

    private Statement statement() {
        final List<String> batchBuffer = new ArrayList<String>();
        return proxy(Statement.class, (proxy, method, args) -> {
            String name = method.getName();
            if ("execute".equals(name) && args != null && args.length > 0) {
                String sql = (String) args[0];
                executedSql.add(sql);
                if (sql.contains("BROKEN")) throw new SQLException("synthetic failure");
                if (sql.startsWith("DROP TABLE") && sql.contains("flydb_schema_history")) {
                    history.clear();
                }
                return false;
            }
            if ("addBatch".equals(name) && args != null && args.length > 0) {
                batchBuffer.add((String) args[0]);
                return null;
            }
            if ("executeBatch".equals(name)) {
                batches++;
                int applied = 0;
                for (String sql : new ArrayList<String>(batchBuffer)) {
                    if (sql.contains("BROKEN")) {
                        int[] counts = new int[applied];
                        for (int i = 0; i < applied; i++) counts[i] = 1;
                        throw new BatchUpdateException("synthetic failure", counts);
                    }
                    executedSql.add(sql);
                    applied++;
                }
                batchBuffer.clear();
                return new int[applied];
            }
            if ("clearBatch".equals(name)) {
                batchBuffer.clear();
                return null;
            }
            if ("executeQuery".equals(name)) return query((String) args[0]);
            if ("isClosed".equals(name)) return false;
            return defaultValue(method.getReturnType());
        });
    }

    private PreparedStatement prepared(final String sql) {
        return proxy(PreparedStatement.class, new InvocationHandler() {
            private final Map<Integer, Object> params = new HashMap<Integer, Object>();

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.startsWith("set") && args != null && args.length >= 2
                        && args[0] instanceof Integer) {
                    params.put((Integer) args[0], "setNull".equals(name) ? null : args[1]);
                    return null;
                }
                if ("execute".equals(name)) return false;
                if ("executeQuery".equals(name)) return query(sql);
                if ("executeUpdate".equals(name)) return update(sql, params);
                return defaultValue(method.getReturnType());
            }
        });
    }

    private ResultSet query(String sql) {
        if (sql.startsWith("SELECT installed_rank")) return resultSet(copyRows(history));
        if (sql.startsWith("SELECT COALESCE(MAX(installed_rank)")) {
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("1", maxRank() + 1);
            return resultSet(singleton(row));
        }
        if (sql.contains("current_schema")) return scalar("public");
        if (sql.contains("DATABASE()")) return scalar("test");
        return resultSet(new ArrayList<Map<String, Object>>());
    }

    private int update(String sql, Map<Integer, Object> params) {
        if (sql.startsWith("INSERT INTO") && sql.contains("installed_rank, version")) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            String[] columns = {"installed_rank", "version", "description", "type", "script",
                    "checksum", "installed_by", "installed_on", "execution_time", "success"};
            for (int i = 0; i < columns.length; i++) row.put(columns[i], params.get(i + 1));
            history.add(row);
        } else if (sql.startsWith("DELETE FROM") && sql.contains("success = ?")) {
            Iterator<Map<String, Object>> iterator = history.iterator();
            while (iterator.hasNext()) {
                if (!Boolean.TRUE.equals(iterator.next().get("success"))) iterator.remove();
            }
        } else if (sql.startsWith("UPDATE") && sql.contains("SET checksum")) {
            for (Map<String, Object> row : history) {
                if (String.valueOf(params.get(2)).equals(row.get("script"))) {
                    row.put("checksum", params.get(1));
                }
            }
        }
        return 1;
    }

    private ResultSet scalar(Object value) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("1", value);
        return resultSet(singleton(row));
    }

    private ResultSet tableRows(Object[] args) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String[] types = args != null && args.length > 3 ? (String[]) args[3] : null;
        if (types != null && types.length == 1 && "TABLE".equals(types[0])) {
            rows.add(tableRow("app_table"));
            rows.add(tableRow("flydb_schema_history"));
            rows.add(tableRow("flydb_schema_lock"));
        }
        return resultSet(rows);
    }

    private static Map<String, Object> tableRow(String name) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("TABLE_NAME", name);
        return row;
    }

    private ResultSet resultSet(final List<Map<String, Object>> rows) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("next".equals(name)) return ++index < rows.size();
                if (name.startsWith("get") && args != null && args.length > 0) {
                    Object key = args[0];
                    Object value = rows.get(index).get(String.valueOf(key));
                    if ("getString".equals(name)) return value == null ? null : String.valueOf(value);
                    if ("getInt".equals(name)) return value == null ? 0 : ((Number) value).intValue();
                    if ("getBoolean".equals(name)) return value != null && (Boolean) value;
                    if ("getTimestamp".equals(name)) return value instanceof Timestamp ? value : null;
                    if ("getObject".equals(name)) return value;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private int maxRank() {
        int max = 0;
        for (Map<String, Object> row : history) {
            max = Math.max(max, ((Number) row.get("installed_rank")).intValue());
        }
        return max;
    }

    private static List<Map<String, Object>> singleton(Map<String, Object> row) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row);
        return rows;
    }

    private static List<Map<String, Object>> copyRows(List<Map<String, Object>> source) {
        return new ArrayList<Map<String, Object>>(source);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        return null;
    }
}
