package com.flydb.core.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.MigrationInfoService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MigrateCommand")
class MigrateCommandTest {

    @Test
    @DisplayName("DataSource 返回 null Connection 时抛连接错误")
    void rejectsNullConnection() {
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
                .build();
        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(com.flydb.core.exception.FlydbException.class)
                .hasMessageContaining("FLYDB-1001");
    }
}

@DisplayName("InfoCommand")
class InfoCommandTest {

    @Test
    @DisplayName("DataSource 返回 null Connection 时抛连接错误")
    void rejectsNullConnection() {
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
                .build();
        assertThatThrownBy(() -> new InfoCommand(cfg).execute())
                .isInstanceOf(com.flydb.core.exception.FlydbException.class)
                .hasMessageContaining("FLYDB-1001");
    }
}
