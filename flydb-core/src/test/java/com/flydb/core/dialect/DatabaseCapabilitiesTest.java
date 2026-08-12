package com.flydb.core.dialect;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.history.SchemaHistoryDdl;
import com.flydb.core.lock.AdvisoryLockMigrationLock;
import com.flydb.core.lock.TableRowLockMigrationLock;
import com.flydb.core.test.JdbcFakes;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("数据库家族能力")
class DatabaseCapabilitiesTest {

    @Test
    @DisplayName("PG 家族提供 PG DDL 与 advisory lock")
    void postgresFamilyCapabilities() throws Exception {
        Connection execution = JdbcFakes.recordingConnection(JdbcFakes.newCapture());
        FlydbConfiguration cfg = configuration(execution);
        Database database = new PostgreSQLDatabaseType().createDatabase(execution, cfg);

        assertThat(database.schemaHistoryDdl().createTableSql("history"))
                .contains("BOOLEAN", "DEFAULT now()");
        assertThat(database.createLock(cfg)).isInstanceOf(AdvisoryLockMigrationLock.class);
    }

    @Test
    @DisplayName("MySQL 家族提供 MySQL DDL 与通用行锁")
    void mysqlFamilyCapabilities() throws Exception {
        Connection execution = JdbcFakes.recordingConnection(JdbcFakes.newCapture());
        FlydbConfiguration cfg = configuration(execution);
        Database database = new MySQLDatabaseType().createDatabase(execution, cfg);

        assertThat(database.schemaHistoryDdl().createTableSql("history"))
                .contains("TINYINT(1)", "ENGINE=InnoDB");
        assertThat(database.createLock(cfg)).isInstanceOf(TableRowLockMigrationLock.class);
    }

    private static FlydbConfiguration configuration(final Connection connection) {
        return FlydbConfiguration.builder().dataSource(new DataSource() {
            @Override public Connection getConnection() { return connection; }
            @Override public Connection getConnection(String u, String p) { return connection; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        }).build();
    }
}
