package com.flydb.boot2.autoconfigure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.flydb.core.Flydb;
import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.MigrationOrder;
import com.flydb.core.migration.VersionSelection;
import com.flydb.core.migration.VersionSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class FlydbAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlydbAutoConfiguration.class));

    @Test
    void mapsVersionSelectionPropertiesToCoreConfiguration() {
        FlydbProperties properties = new FlydbProperties();
        properties.setVersionSelection("family");
        properties.setVersionSource("directory");
        properties.setTargetVersion("20230531");
        properties.setDirectoryGlob("mysql/param/**");
        properties.setMigrationOrder("directory-version");
        properties.setPlaceholderReplacement(false);

        FlydbConfiguration configuration = properties.toCoreConfiguration(
                new DriverManagerDataSource(), getClass().getClassLoader());

        assertThat(configuration.versionSelection().mode()).isEqualTo(VersionSelection.Mode.FAMILY);
        assertThat(configuration.versionSelection().source()).isEqualTo(VersionSource.DIRECTORY);
        assertThat(configuration.targetVersion()).isEqualTo(MigrationVersion.parse("20230531"));
        assertThat(configuration.directoryGlob()).isEqualTo("mysql/param/**");
        assertThat(configuration.migrationOrder()).isEqualTo(MigrationOrder.DIRECTORY_VERSION);
        assertThat(configuration.placeholderReplacement()).isFalse();
    }

    @Test
    void backsOffCompletelyWhenDisabled() {
        contextRunner.withPropertyValues("flydb.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(Flydb.class);
                    assertThat(context).doesNotHaveBean(FlydbMigrationInitializer.class);
                    assertThat(context).doesNotHaveBean(FlydbProperties.class);
                });
    }

    @Test
    void failsFastWithoutApplicationDataSourceOrFlydbUrl() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(FlydbException.class);
            Throwable rootCause = rootCause(context.getStartupFailure());
            assertThat(((FlydbException) rootCause).errorCode())
                    .isEqualTo(ErrorCode.MISSING_REQUIRED_CONFIG);
        });
    }

    @Test
    void dedicatedUrlIgnoresApplicationDataSourceAndMigrates() {
        String url = h2Url("boot2_dedicated");
        contextRunner.withBean(DataSource.class, BrokenDataSource::new)
                .withPropertyValues(
                        "flydb.url=" + url,
                        "flydb.user=sa",
                        "flydb.password=",
                        "flydb.driver=org.h2.Driver",
                        "flydb.database-type=mysql",
                        "flydb.locations=classpath:db/starter")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Flydb.class);
                    assertThat(tableExists(url, "STARTER_PROBE")).isTrue();
                });
    }

    @Test
    void reusesApplicationDataSourceAndOrdersDependentBeansAfterMigration() {
        contextRunner.withUserConfiguration(ApplicationDataSourceConfiguration.class)
                .withPropertyValues(
                        "flydb.database-type=mysql",
                        "flydb.locations=classpath:db/starter")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FlydbMigrationInitializer.class);
                    assertThat(context).hasSingleBean(StartupProbe.class);
                    assertThat(context.getBean(StartupProbe.class).tableWasReady).isTrue();
                });
    }

    @Test
    void userFlydbBeanWins() {
        contextRunner.withUserConfiguration(CustomFlydbConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(Flydb.class);
                    assertThat(context.getBean(Flydb.class))
                            .isSameAs(context.getBean("customFlydb"));
                });
    }

    private static String h2Url(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    }

    private static boolean tableExists(String url, String table) {
        try (Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
             ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    static final class BrokenDataSource implements DataSource {
        @Override public Connection getConnection() { throw new AssertionError("应用 DataSource 不应被使用"); }
        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("unsupported"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationDataSourceConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(h2Url("boot2_application"), "sa", "");
        }

        @Bean
        @DependsOnDatabaseInitialization
        StartupProbe startupProbe(DataSource dataSource) {
            return new StartupProbe(tableExists(h2Url("boot2_application"), "STARTER_PROBE"));
        }
    }

    static final class StartupProbe {
        private final boolean tableWasReady;

        StartupProbe(boolean tableWasReady) {
            this.tableWasReady = tableWasReady;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomFlydbConfiguration {
        @Bean
        DataSource customDataSource() {
            return new DriverManagerDataSource(h2Url("boot2_custom"), "sa", "");
        }

        @Bean
        Flydb customFlydb(DataSource customDataSource) {
            return Flydb.configure()
                    .dataSource(customDataSource)
                    .databaseType("mysql")
                    .locations("classpath:db/starter")
                    .load();
        }
    }
}
