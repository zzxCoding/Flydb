package com.flydb.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.MigrationInfoService;

import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("migrate() 返回 MigrateResult")
    void migrateReturnsResult() {
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
                .load();
        Flydb flydb = new Flydb(cfg);
        MigrateResult result = flydb.migrate();
        assertThat(result).isNotNull();
        assertThat(result.executed()).isNotNull();
    }

    @Test
    @DisplayName("info() 返回 MigrationInfoService")
    void infoReturnsService() {
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
                .load();
        Flydb flydb = new Flydb(cfg);
        MigrationInfoService svc = flydb.info();
        assertThat(svc).isNotNull();
        assertThat(svc.all()).isEmpty();
    }
}