package com.flydb.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.MigrationInfoService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flydb 门面单测（设计 02 §1）。
 */
@DisplayName("Flydb")
class FlydbTest {

    @Test
    @DisplayName("configure() 返回 Builder")
    void configureReturnsBuilder() {
        FlydbConfiguration.Builder builder = Flydb.configure();
        assertThat(builder).isNotNull();
    }

    @Test
    @DisplayName("migrate() 对 DataSource 返回 null Connection 快速报连接错误")
    void migrateRejectsNullConnection() {
        FlydbConfiguration cfg = Flydb.configure()
                .dataSource(new javax.sql.DataSource() {
                    @Override public java.sql.Connection getConnection() { return null; }
                    @Override public java.sql.Connection getConnection(String username, String password) { return null; }
                    @Override public java.io.PrintWriter getLogWriter() { return null; }
                    @Override public void setLogWriter(java.io.PrintWriter out) {}
                    @Override public void setLoginTimeout(int seconds) {}
                    @Override public int getLoginTimeout() { return 0; }
                    @Override public java.util.logging.Logger getParentLogger() { return null; }
                    @Override public <T> T unwrap(Class<T> iface) { return null; }
                    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
                })
                .build();
        Flydb flydb = new Flydb(cfg);
        assertThatThrownBy(flydb::migrate)
                .isInstanceOf(com.flydb.core.exception.FlydbException.class)
                .hasMessageContaining("FLYDB-1001");
    }

    @Test
    @DisplayName("info() 对 DataSource 返回 null Connection 快速报连接错误")
    void infoRejectsNullConnection() {
        FlydbConfiguration cfg = Flydb.configure()
                .dataSource(new javax.sql.DataSource() {
                    @Override public java.sql.Connection getConnection() { return null; }
                    @Override public java.sql.Connection getConnection(String username, String password) { return null; }
                    @Override public java.io.PrintWriter getLogWriter() { return null; }
                    @Override public void setLogWriter(java.io.PrintWriter out) {}
                    @Override public void setLoginTimeout(int seconds) {}
                    @Override public int getLoginTimeout() { return 0; }
                    @Override public java.util.logging.Logger getParentLogger() { return null; }
                    @Override public <T> T unwrap(Class<T> iface) { return null; }
                    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
                })
                .build();
        Flydb flydb = new Flydb(cfg);
        assertThatThrownBy(flydb::info)
                .isInstanceOf(com.flydb.core.exception.FlydbException.class)
                .hasMessageContaining("FLYDB-1001");
    }
}
