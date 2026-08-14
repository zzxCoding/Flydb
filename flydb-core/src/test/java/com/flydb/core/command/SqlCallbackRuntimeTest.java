package com.flydb.core.command;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.api.FlydbConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SQL Callback")
class SqlCallbackRuntimeTest {

    @TempDir
    Path migrations;

    @Test
    @DisplayName("beforeValidate.sql 被自动发现并使用迁移解析器执行")
    void sqlCallbackIsDiscoveredByEventName() throws Exception {
        Files.write(migrations.resolve("beforeValidate.sql"),
                "INSERT INTO audit(event) VALUES ('validate');".getBytes("UTF-8"));
        List<String> executed = new ArrayList<String>();
        FlydbConfiguration cfg = FlydbConfiguration.builder()
                .dataSource(dataSource(executed))
                .locations("filesystem:" + migrations)
                .build();

        new ValidateCommand(cfg).execute();

        assertThat(executed).contains("INSERT INTO audit(event) VALUES ('validate')");
    }

    private static DataSource dataSource(final List<String> executed) {
        final Connection connection = proxy(Connection.class, (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) return metadata();
            if ("createStatement".equals(method.getName())) return statement(executed);
            return defaultValue(method.getReturnType());
        });
        return new DataSource() {
            @Override public Connection getConnection() { return connection; }
            @Override public Connection getConnection(String u, String p) { return connection; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static DatabaseMetaData metadata() {
        return proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if ("getURL".equals(method.getName())) return "jdbc:postgresql://localhost/test";
            if ("getDatabaseProductName".equals(method.getName())) return "PostgreSQL";
            if ("getUserName".equals(method.getName())) return "tester";
            return defaultValue(method.getReturnType());
        });
    }

    private static Statement statement(final List<String> executed) {
        return proxy(Statement.class, (proxy, method, args) -> {
            if ("executeQuery".equals(method.getName())) {
                String sql = (String) args[0];
                return sql.contains("current_schema") ? oneRow("public") : emptyRows();
            }
            if ("execute".equals(method.getName()) && args != null && args.length > 0) {
                executed.add((String) args[0]);
                return false;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static ResultSet emptyRows() {
        return proxy(ResultSet.class, (proxy, method, args) ->
                "next".equals(method.getName()) ? false : defaultValue(method.getReturnType()));
    }

    private static ResultSet oneRow(final String value) {
        final boolean[] first = {true};
        return proxy(ResultSet.class, (proxy, method, args) -> {
            if ("next".equals(method.getName())) {
                boolean result = first[0]; first[0] = false; return result;
            }
            if ("getString".equals(method.getName())) return value;
            return defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
