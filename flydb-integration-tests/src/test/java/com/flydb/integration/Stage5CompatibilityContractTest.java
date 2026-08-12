package com.flydb.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.command.InfoCommand;
import com.flydb.core.command.MigrateCommand;
import com.flydb.core.migration.MigrationState;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "flydb.integration.enabled", matches = "true")
@DisplayName("阶段 5 兼容家族数据库契约")
class Stage5CompatibilityContractTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("flydb").withUsername("flydb").withPassword("flydb");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("flydb").withUsername("flydb").withPassword("flydb");

    private static DatabaseTestSupport mysql;
    private static DatabaseTestSupport postgres;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void createSupports() {
        mysql = new JdbcContainerTestSupport(MYSQL);
        postgres = new JdbcContainerTestSupport(POSTGRES);
    }

    @Test
    @DisplayName("TiDB 方言在 MySQL 8 上跑通 MySQL 家族完整迁移契约")
    void tidbUsesMySqlFamilyContract() throws Exception {
        MigrateResult result = migrate(mysql.dataSource(), "tidb", "flydb_tidb_history",
                "V1__tidb.sql", "CREATE TABLE tidb_contract(id BIGINT PRIMARY KEY)");

        assertThat(result.executed()).containsExactly("V1__tidb.sql");
    }

    @Test
    @DisplayName("OceanBase-MySQL 方言在 MySQL 8 上跑通完整迁移契约")
    void oceanBaseMySqlUsesMySqlFamilyContract() throws Exception {
        DataSource dataSource = new OceanBaseMySqlDataSource(mysql.dataSource());
        MigrateResult result = migrate(dataSource, null, "flydb_ob_history",
                "V1__oceanbase.sql", "CREATE TABLE ob_contract(id BIGINT PRIMARY KEY)");

        assertThat(result.executed()).containsExactly("V1__oceanbase.sql");
    }

    @Test
    @DisplayName("openGauss 方言在 PostgreSQL 16 上跑通 PG 家族完整迁移契约")
    void openGaussUsesPostgresFamilyContract() throws Exception {
        MigrateResult result = migrate(postgres.dataSource(), "opengauss", "flydb_og_history",
                "V1__opengauss.sql", "CREATE TABLE opengauss_contract(id BIGINT PRIMARY KEY)");

        assertThat(result.executed()).containsExactly("V1__opengauss.sql");
    }

    @Test
    @DisplayName("KingbaseES 方言在 PostgreSQL 16 上跑通 PG 家族和 advisory lock 契约")
    void kingbaseUsesPostgresFamilyContract() throws Exception {
        MigrateResult result = migrate(postgres.dataSource(), "kingbasees", "flydb_kb_history",
                "V1__kingbase.sql", "CREATE TABLE kingbase_contract(id BIGINT PRIMARY KEY)");

        assertThat(result.executed()).containsExactly("V1__kingbase.sql");
        assertThat(result.warnings()).isEmpty();
    }

    private MigrateResult migrate(DataSource dataSource, String databaseType, String historyTable,
                                  String script, String sql) throws Exception {
        Path migrations = Files.createDirectory(temporaryDirectory.resolve(databaseType == null
                ? "oceanbase" : databaseType));
        Files.write(migrations.resolve(script), (sql + ";").getBytes("UTF-8"));
        FlydbConfiguration.Builder builder = FlydbConfiguration.builder()
                .dataSource(dataSource).table(historyTable)
                .locations("filesystem:" + migrations).lockTimeoutSeconds(20);
        if (databaseType != null) builder.databaseType(databaseType);
        FlydbConfiguration configuration = builder.build();
        MigrateResult result = new MigrateCommand(configuration).execute();
        assertThat(new InfoCommand(configuration).execute().all()).singleElement()
                .satisfies(info -> assertThat(info.state()).isEqualTo(MigrationState.SUCCESS));
        return result;
    }

    /** 仅替换 OceanBase 专有探测结果，其余 JDBC 调用全部落到真实 MySQL。 */
    private static final class OceanBaseMySqlDataSource implements DataSource {
        private final DataSource delegate;

        OceanBaseMySqlDataSource(DataSource delegate) { this.delegate = delegate; }

        @Override public Connection getConnection() throws java.sql.SQLException {
            return wrap(delegate.getConnection());
        }
        @Override public Connection getConnection(String user, String password)
                throws java.sql.SQLException { return wrap(delegate.getConnection(user, password)); }
        @Override public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
            return delegate.getLogWriter();
        }
        @Override public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException {
            delegate.setLogWriter(out);
        }
        @Override public void setLoginTimeout(int seconds) throws java.sql.SQLException {
            delegate.setLoginTimeout(seconds);
        }
        @Override public int getLoginTimeout() throws java.sql.SQLException {
            return delegate.getLoginTimeout();
        }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }

        private static Connection wrap(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("getMetaData".equals(method.getName())) {
                            return metadata(delegate.getMetaData());
                        }
                        if ("createStatement".equals(method.getName())) {
                            return statement(delegate.createStatement());
                        }
                        return invoke(delegate, method, args);
                    });
        }

        private static DatabaseMetaData metadata(DatabaseMetaData delegate) {
            return (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                        if ("getURL".equals(method.getName())) return "jdbc:oceanbase://localhost/flydb";
                        if ("getDatabaseProductName".equals(method.getName())) return "OceanBase";
                        return invoke(delegate, method, args);
                    });
        }

        private static Statement statement(Statement delegate) {
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                    new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                        if ("executeQuery".equals(method.getName()) && args != null
                                && "SHOW VARIABLES LIKE 'ob_compatibility_mode'".equals(args[0])) {
                            return compatibilityModeResult();
                        }
                        return invoke(delegate, method, args);
                    });
        }

        private static ResultSet compatibilityModeResult() {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class}, new java.lang.reflect.InvocationHandler() {
                        private boolean beforeFirst = true;

                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("next".equals(method.getName())) {
                                boolean result = beforeFirst;
                                beforeFirst = false;
                                return result;
                            }
                            if ("getString".equals(method.getName())) {
                                return Integer.valueOf(2).equals(args[0]) ? "mysql"
                                        : "ob_compatibility_mode";
                            }
                            return defaultValue(method.getReturnType());
                        }
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}
