package com.flydb.core.dialect;

import java.sql.Connection;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.history.SchemaHistoryDdl;
import com.flydb.core.lock.AdvisoryLockMigrationLock;
import com.flydb.core.lock.MigrationLock;
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

    @Test
    @DisplayName("Oracle 家族（含 OceanBase Oracle）使用锁表行锁")
    void oracleFamilyUsesTableRowLock() throws Exception {
        Connection execution = JdbcFakes.recordingConnection(JdbcFakes.newCapture());
        FlydbConfiguration cfg = configuration(execution);
        Database oracle = new OracleDatabaseType().createDatabase(execution, cfg);
        Database oceanBaseOracle = new OceanBaseOracleDatabase(execution);

        assertThat(oracle.createLock(cfg)).isInstanceOf(TableRowLockMigrationLock.class);
        assertThat(oceanBaseOracle.createLock(cfg))
                .isInstanceOf(TableRowLockMigrationLock.class);
    }

    @Test
    @DisplayName("Oracle 家族 clean 使用 PURGE 与 user_sequences 的专用策略")
    void oracleFamilyUsesOracleCleanStrategy() throws Exception {
        Connection execution = JdbcFakes.recordingConnection(JdbcFakes.newCapture());

        assertThat(new OracleDatabaseType().createDatabase(execution, null).cleanStrategy())
                .isInstanceOf(OracleCleanStrategy.class);
        assertThat(new OceanBaseOracleDatabase(execution).cleanStrategy())
                .isInstanceOf(OracleCleanStrategy.class);
        assertThat(new DmDatabase(execution, false).cleanStrategy())
                .isInstanceOf(OracleCleanStrategy.class);
    }

    @Test
    @DisplayName("KingbaseES 不支持 advisory lock 时降级锁表并给出警告")
    void kingbaseFallsBackToTableLockWithWarning() {
        Connection execution = unsupportedAdvisoryConnection();
        FlydbConfiguration cfg = configuration(execution);

        MigrationLock lock = new KingbaseESDatabase(execution).createLock(cfg);

        assertThat(lock.warnings()).singleElement()
                .asString().contains("KingbaseES", "锁表");
    }

    @Test
    @DisplayName("达梦大小写敏感实例为历史表和锁表引用标识符")
    void dmCaseSensitiveModeQuotesBookkeepingTables() throws Exception {
        Connection connection = queryConnection(
                "SELECT SF_GET_CASE_SENSITIVE_FLAG() FROM dual", "1");

        DmDatabase database = (DmDatabase) new DmDatabaseType()
                .createDatabase(connection, null);

        assertThat(database.caseSensitive()).isTrue();
        assertThat(database.schemaHistoryDdl().createTableSql("flydb_schema_history"))
                .startsWith("CREATE TABLE \"flydb_schema_history\"");
        assertThat(database.schemaHistoryDdl().createLockTableSql("flydb_schema_lock"))
                .startsWith("CREATE TABLE \"flydb_schema_lock\"");
    }

    @Test
    @DisplayName("达梦大小写敏感实例获取锁时引用带引号的锁表")
    void dmCaseSensitiveModeQuotesLockStatements() {
        List<String> sql = new ArrayList<String>();
        Connection connection = preparedSqlConnection(sql);
        FlydbConfiguration cfg = configuration(connection);

        new DmDatabase(connection, true).createLock(cfg).acquire();

        assertThat(sql).containsExactly(
                "SELECT lock_id FROM \"flydb_schema_lock\" WHERE lock_id = 1 FOR UPDATE",
                "UPDATE \"flydb_schema_lock\" SET locked_by=?, "
                        + "locked_at=CURRENT_TIMESTAMP WHERE lock_id=1");
    }

    private static Connection unsupportedAdvisoryConnection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        throw new SQLException("function pg_advisory_unlock does not exist");
                    }
                    return JdbcFakes.defaultValue(method.getReturnType());
                });
    }

    private static Connection preparedSqlConnection(final List<String> sql) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        sql.add((String) args[0]);
                        return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{java.sql.PreparedStatement.class},
                                (statement, statementMethod, statementArgs) ->
                                        JdbcFakes.defaultValue(statementMethod.getReturnType()));
                    }
                    return JdbcFakes.defaultValue(method.getReturnType());
                });
    }

    private static Connection queryConnection(final String expectedSql, final String value) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("createStatement".equals(method.getName())) {
                        return Proxy.newProxyInstance(java.sql.Statement.class.getClassLoader(),
                                new Class<?>[]{java.sql.Statement.class},
                                (statement, statementMethod, statementArgs) -> {
                                    if ("executeQuery".equals(statementMethod.getName())) {
                                        assertThat(statementArgs[0]).isEqualTo(expectedSql);
                                        return singleValueResultSet(value);
                                    }
                                    return JdbcFakes.defaultValue(statementMethod.getReturnType());
                                });
                    }
                    return JdbcFakes.defaultValue(method.getReturnType());
                });
    }

    private static Object singleValueResultSet(final String value) {
        return Proxy.newProxyInstance(java.sql.ResultSet.class.getClassLoader(),
                new Class<?>[]{java.sql.ResultSet.class}, new java.lang.reflect.InvocationHandler() {
                    private boolean beforeFirst = true;

                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        if ("next".equals(method.getName())) {
                            boolean result = beforeFirst;
                            beforeFirst = false;
                            return result;
                        }
                        if ("getString".equals(method.getName())) return value;
                        return JdbcFakes.defaultValue(method.getReturnType());
                    }
                });
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
