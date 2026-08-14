package com.flydb.core.command;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrationInfoService;
import com.flydb.core.migration.MigrationState;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InfoCommand 运行时接线")
class InfoCommandRuntimeTest {

    @TempDir
    Path migrations;

    @Test
    @DisplayName("历史表不存在时解析本地脚本并返回 PENDING")
    void missingHistoryReturnsLocalMigrationsAsPending() throws Exception {
        Files.write(migrations.resolve("V1__init.sql"), "CREATE TABLE t(id INT);".getBytes("UTF-8"));
        FlydbConfiguration cfg = FlydbConfiguration.builder()
                .dataSource(emptyPostgresDataSource())
                .locations("filesystem:" + migrations)
                .build();

        MigrationInfoService info = new InfoCommand(cfg).execute();

        assertThat(info.all()).hasSize(1);
        assertThat(info.all().get(0).state()).isEqualTo(MigrationState.PENDING);
    }

    private static DataSource emptyPostgresDataSource() {
        final Connection connection = proxy(Connection.class, (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                return proxy(DatabaseMetaData.class, (p, m, a) -> {
                    if ("getURL".equals(m.getName())) return "jdbc:postgresql://localhost/test";
                    if ("getDatabaseProductName".equals(m.getName())) return "PostgreSQL";
                    if ("getUserName".equals(m.getName())) return "tester";
                    return defaultValue(m.getReturnType());
                });
            }
            if ("createStatement".equals(method.getName())) {
                return proxy(Statement.class, (p, m, a) -> {
                    if ("executeQuery".equals(m.getName())) return emptyResultSet();
                    return defaultValue(m.getReturnType());
                });
            }
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

    private static ResultSet emptyResultSet() {
        return proxy(ResultSet.class, (proxy, method, args) -> {
            if ("next".equals(method.getName())) return false;
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
