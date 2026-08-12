package com.flydb.core.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.MigrationInfoService;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MigrateCommand")
class MigrateCommandTest {

    @Test
    @DisplayName("execute 返回不可为 null 的 MigrateResult")
    void executeReturnsResult() {
        FlydbConfiguration cfg = FlydbConfiguration.builder()
                .dataSource(new javax.sql.DataSource() {
                    @Override public java.sql.Connection getConnection() { return null; }
                    @Override public java.sql.Connection getConnection(String u, String p) { return null; }
                    @Override public java.io.PrintWriter getLogWriter() { return null; }
                    @Override public void setLogWriter(java.io.PrintWriter w) {}
                    @Override public void setLoginTimeout(int t) {}
                    @Override public int getLoginTimeout() { return 0; }
                    @Override public java.util.logging.Logger getParentLogger() { return null; }
                    @Override public <T> T unwrap(Class<T> i) { return null; }
                    @Override public boolean isWrapperFor(Class<?> i) { return false; }
                })
                .load();
        MigrateResult result = new MigrateCommand(cfg).execute();
        assertThat(result).isNotNull();
        assertThat(result.executed()).isEmpty();
    }
}

@DisplayName("InfoCommand")
class InfoCommandTest {

    @Test
    @DisplayName("execute 返回不可为 null 的 MigrationInfoService")
    void executeReturnsService() {
        FlydbConfiguration cfg = FlydbConfiguration.builder()
                .dataSource(new javax.sql.DataSource() {
                    @Override public java.sql.Connection getConnection() { return null; }
                    @Override public java.sql.Connection getConnection(String u, String p) { return null; }
                    @Override public java.io.PrintWriter getLogWriter() { return null; }
                    @Override public void setLogWriter(java.io.PrintWriter w) {}
                    @Override public void setLoginTimeout(int t) {}
                    @Override public int getLoginTimeout() { return 0; }
                    @Override public java.util.logging.Logger getParentLogger() { return null; }
                    @Override public <T> T unwrap(Class<T> i) { return null; }
                    @Override public boolean isWrapperFor(Class<?> i) { return false; }
                })
                .load();
        MigrationInfoService svc = new InfoCommand(cfg).execute();
        assertThat(svc).isNotNull();
        assertThat(svc.all()).isEmpty();
    }
}