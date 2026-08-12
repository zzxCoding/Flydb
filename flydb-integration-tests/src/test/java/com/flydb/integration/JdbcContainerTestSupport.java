package com.flydb.integration;

import javax.sql.DataSource;

import org.testcontainers.containers.JdbcDatabaseContainer;

/** 已启动的标准 JDBC Testcontainers 容器适配。 */
final class JdbcContainerTestSupport implements DatabaseTestSupport {

    private final JdbcDatabaseContainer<?> container;
    private final DataSource dataSource;

    JdbcContainerTestSupport(JdbcDatabaseContainer<?> container) {
        this.container = container;
        this.dataSource = new DriverManagerDataSource(container.getJdbcUrl(),
                container.getUsername(), container.getPassword());
    }

    @Override public DataSource dataSource() { return dataSource; }
    @Override public String jdbcUrl() { return container.getJdbcUrl(); }
    @Override public void resetSchema() { }
    @Override public void close() { }
}
