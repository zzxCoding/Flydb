package com.flydb.integration;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.RepairResult;
import com.flydb.core.command.CleanCommand;
import com.flydb.core.command.InfoCommand;
import com.flydb.core.command.MigrateCommand;
import com.flydb.core.command.RepairCommand;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "flydb.integration.enabled", matches = "true")
@DisplayName("阶段 4 PostgreSQL/MySQL 数据库契约")
class Stage4DatabaseContractTest {

    private static PostgreSQLContainer<?> POSTGRES;
    private static MySQLContainer<?> MYSQL;

    @BeforeAll
    static void startSelectedContainers() {
        if (IntegrationDatabaseSelector.mysqlFamilySelected()) {
            MYSQL = new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("flydb")
                    .withUsername("flydb")
                    .withPassword("flydb");
            MYSQL.start();
        }
        if (IntegrationDatabaseSelector.postgresFamilySelected()) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("flydb")
                    .withUsername("flydb")
                    .withPassword("flydb");
            POSTGRES.start();
        }
    }

    @AfterAll
    static void stopSelectedContainers() {
        if (MYSQL != null) MYSQL.stop();
        if (POSTGRES != null) POSTGRES.stop();
    }

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("PostgreSQL 失败无痕自愈且 advisory lock 串行化并发 migrate")
    void postgresRollbackAndAdvisoryLockContract() throws Exception {
        IntegrationDatabaseSelector.assumePostgresFamily();
        Path migrations = Files.createDirectory(tempDirectory.resolve("pg"));
        write(migrations, "V1__init.sql", "CREATE TABLE pg_ok(id INT PRIMARY KEY);");
        FlydbConfiguration cfg = configuration(POSTGRES, migrations, true);
        assertThat(new MigrateCommand(cfg).execute().executed()).containsExactly("V1__init.sql");

        write(migrations, "V2__broken.sql", "CREATE TABLE pg_partial(id INT); BROKEN SQL;");
        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("V2__broken.sql");
        assertThat(historyCount(POSTGRES, "V2__broken.sql", false)).isZero();
        assertThat(tableExists(POSTGRES, "pg_partial")).isFalse();

        write(migrations, "V2__broken.sql", "CREATE TABLE pg_recovered(id INT);");
        assertThat(new MigrateCommand(cfg).execute().executed()).containsExactly("V2__broken.sql");
        write(migrations, "V3__concurrent.sql",
                "SELECT pg_sleep(1); CREATE TABLE pg_concurrent(id INT);");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> migrate = () -> new MigrateCommand(cfg).execute().executed().size();
            List<Future<Integer>> results = pool.invokeAll(Arrays.asList(migrate, migrate));
            assertThat(results.get(0).get() + results.get(1).get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(historyCount(POSTGRES, "V3__concurrent.sql", true)).isEqualTo(1);
    }

    @Test
    @DisplayName("MySQL 失败记 FAILED、阻断 migrate、repair 后恢复并可 clean")
    void mysqlFailureRepairAndCleanContract() throws Exception {
        IntegrationDatabaseSelector.assumeMysqlFamily();
        Path migrations = Files.createDirectory(tempDirectory.resolve("mysql"));
        write(migrations, "V1__init.sql", "CREATE TABLE mysql_ok(id INT PRIMARY KEY);");
        FlydbConfiguration cfg = configuration(MYSQL, migrations, false);
        new MigrateCommand(cfg).execute();

        write(migrations, "V2__broken.sql",
                "CREATE TABLE mysql_partial(id INT); BROKEN SQL;");
        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class);
        assertThat(historyCount(MYSQL, "V2__broken.sql", false)).isEqualTo(1);
        assertThatThrownBy(() -> new MigrateCommand(cfg).execute())
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR);

        RepairResult repair = new RepairCommand(cfg).execute();
        assertThat(repair.removedFailedRecords()).containsExactly("V2__broken.sql");
        write(migrations, "V2__broken.sql",
                "DROP TABLE mysql_partial; CREATE TABLE mysql_recovered(id INT);");
        assertThat(new MigrateCommand(cfg).execute().executed()).containsExactly("V2__broken.sql");
        assertThat(new InfoCommand(cfg).execute().all())
                .allSatisfy(info -> assertThat(info.state()).isEqualTo(MigrationState.SUCCESS));

        write(migrations, "V3__concurrent.sql",
                "SELECT SLEEP(1); CREATE TABLE mysql_concurrent(id INT);");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> migrate = () -> new MigrateCommand(cfg).execute().executed().size();
            List<Future<Integer>> results = pool.invokeAll(Arrays.asList(migrate, migrate));
            assertThat(results.get(0).get() + results.get(1).get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(historyCount(MYSQL, "V3__concurrent.sql", true)).isEqualTo(1);

        new CleanCommand(cfg).execute();
        assertThat(tableExists(MYSQL, "flydb_schema_history")).isFalse();
        assertThat(tableExists(MYSQL, "mysql_ok")).isFalse();
    }

    private static FlydbConfiguration configuration(
            org.testcontainers.containers.JdbcDatabaseContainer<?> container,
            Path migrations, boolean cleanDisabled) {
        return FlydbConfiguration.builder()
                .dataSource(new DriverManagerDataSource(container.getJdbcUrl(),
                        container.getUsername(), container.getPassword()))
                .locations("filesystem:" + migrations)
                .cleanDisabled(cleanDisabled)
                .lockTimeoutSeconds(10)
                .build();
    }

    private static int historyCount(org.testcontainers.containers.JdbcDatabaseContainer<?> container,
                                    String script, boolean success) throws SQLException {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(),
                container.getUsername(), container.getPassword());
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM flydb_schema_history WHERE script=? AND success=?")) {
            statement.setString(1, script);
            statement.setBoolean(2, success);
            try (java.sql.ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean tableExists(
            org.testcontainers.containers.JdbcDatabaseContainer<?> container,
            String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(),
                container.getUsername(), container.getPassword());
             java.sql.ResultSet rs = connection.getMetaData().getTables(
                     null, null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    private static void write(Path directory, String name, String sql) throws Exception {
        Files.write(directory.resolve(name), sql.getBytes("UTF-8"));
    }

    private static final class DriverManagerDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;

        DriverManagerDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
