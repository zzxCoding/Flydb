package com.flydb.cli.driver;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.logging.Logger;

import javax.sql.DataSource;

/** 直接调用动态加载的 Driver，避免 DriverManager 的类加载器可见性限制。 */
final class DriverDataSource implements DataSource, AutoCloseable {

    private final Driver driver;
    private final String url;
    private final String user;
    private final String password;
    private final Set<Connection> connections = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<Connection, Boolean>()));
    private volatile boolean closed;

    DriverDataSource(Driver driver, String url, String user, String password) {
        this.driver = driver;
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connect(user, password);
    }

    @Override
    public Connection getConnection(String username, String suppliedPassword) throws SQLException {
        return connect(username, suppliedPassword);
    }

    private Connection connect(String username, String suppliedPassword) throws SQLException {
        Properties properties = new Properties();
        if (username != null) properties.setProperty("user", username);
        if (suppliedPassword != null) properties.setProperty("password", suppliedPassword);
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("驱动 " + driver.getClass().getName()
                    + " 不接受 JDBC URL: " + url);
        }
        if (closed) {
            connection.close();
            throw new SQLException("动态驱动 DataSource 已关闭");
        }
        connections.add(connection);
        return connection;
    }

    @Override
    public void close() {
        closed = true;
        Connection[] snapshot;
        synchronized (connections) {
            snapshot = connections.toArray(new Connection[connections.size()]);
            connections.clear();
        }
        for (Connection connection : snapshot) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 关闭其余连接。
            }
        }
    }

    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter out) { }
    @Override public void setLoginTimeout(int seconds) { }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("不是 " + iface.getName() + " 的包装器");
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
