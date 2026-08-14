package com.flydb.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.MigrationOrder;
import com.flydb.core.migration.VersionSelection;
import com.flydb.core.migration.VersionSource;

/**
 * FlydbConfiguration 不可变性与 Builder 校验测试（设计 02 §2）。
 *
 * <p>不可变 + 防御性拷贝；{@code load()} 快速失败，杜绝旧原型「配置了但没生效」的静默失效。
 */
class FlydbConfigurationTest {

    @Test
    void applies_documented_defaults() {
        FlydbConfiguration c = FlydbConfiguration.builder().url("jdbc:x").build();

        assertThat(c.locations()).containsExactly("classpath:db/migration");
        assertThat(c.encoding()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(c.table()).isEqualTo("flydb_schema_history");
        assertThat(c.baselineVersion()).isEqualTo(MigrationVersion.parse("1"));
        assertThat(c.baselineOnMigrate()).isFalse();
        assertThat(c.validateOnMigrate()).isTrue();
        assertThat(c.outOfOrder()).isFalse();
        assertThat(c.targetVersion()).isNull();
        assertThat(c.startVersion()).isNull();
        assertThat(c.endVersion()).isNull();
        assertThat(c.cleanDisabled()).isTrue();
        assertThat(c.lockTimeoutSeconds()).isEqualTo(60);
        assertThat(c.batchSize()).isEqualTo(1);
        assertThat(c.placeholderPrefix()).isEqualTo("${");
        assertThat(c.placeholderSuffix()).isEqualTo("}");
        assertThat(c.placeholderReplacement()).isTrue();
        assertThat(c.placeholders()).isEmpty();
        assertThat(c.databaseType()).isNull();
        assertThat(c.classLoader()).isNotNull();
        assertThat(c.sqlMigrationPrefix()).isEqualTo("V");
        assertThat(c.repeatableMigrationPrefix()).isEqualTo("R");
        assertThat(c.undoMigrationPrefix()).isEqualTo("U");
        assertThat(c.sqlMigrationSeparator()).isEqualTo("__");
        assertThat(c.sqlMigrationSuffix()).isEqualTo(".sql");
    }

    @Test
    void batch_size_defaults_to_one_and_rejects_smaller_values() {
        FlydbConfiguration c = FlydbConfiguration.builder()
                .url("jdbc:x").batchSize(100).build();
        assertThat(c.batchSize()).isEqualTo(100);

        assertThatThrownBy(() -> FlydbConfiguration.builder()
                .url("jdbc:x").batchSize(0).build())
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("flydb.batch-size");
    }

    @Test
    void url_path_stores_credentials_and_leaves_data_source_null() {
        // DriverDataSource 由阶段 3 在运行期构造；阶段 1 仅持有连接参数
        FlydbConfiguration c = FlydbConfiguration.builder()
                .url("jdbc:dm://localhost:5236").user("SYSDBA").password("secret").build();

        assertThat(c.url()).isEqualTo("jdbc:dm://localhost:5236");
        assertThat(c.user()).isEqualTo("SYSDBA");
        assertThat(c.password()).isEqualTo("secret");
        assertThat(c.dataSource()).isNull();
    }

    @Test
    void data_source_path_keeps_reference_and_url_null() {
        DataSource ds = new StubDataSource();

        FlydbConfiguration c = FlydbConfiguration.builder().dataSource(ds).build();

        assertThat(c.dataSource()).isSameAs(ds);
        assertThat(c.url()).isNull();
    }

    @Test
    void load_requires_either_url_or_data_source() {
        assertThatThrownBy(() -> FlydbConfiguration.builder().build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MISSING_REQUIRED_CONFIG);
    }

    @Test
    void load_rejects_both_url_and_data_source() {
        assertThatThrownBy(() -> FlydbConfiguration.builder()
                .url("jdbc:x").dataSource(new StubDataSource()).build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MISSING_REQUIRED_CONFIG);
    }

    @Test
    void baseline_version_string_is_parsed() {
        FlydbConfiguration c = FlydbConfiguration.builder()
                .url("jdbc:x").baselineVersion("2.1").build();

        assertThat(c.baselineVersion()).isEqualTo(MigrationVersion.parse("2.1"));
    }

    @Test
    void target_version_string_is_parsed_as_exact_selection() {
        FlydbConfiguration c = FlydbConfiguration.builder()
                .url("jdbc:x").targetVersion("2.1").build();

        assertThat(c.targetVersion()).isEqualTo(MigrationVersion.parse("2.1"));
        assertThat(c.versionSelection().mode()).isEqualTo(VersionSelection.Mode.EXACT);
        assertThat(c.versionSelection().source()).isEqualTo(VersionSource.FILE);
        assertThat(c.startVersion()).isNull();
        assertThat(c.endVersion()).isNull();
    }

    @Test
    void path_filters_and_directory_order_are_explicit_opt_in_rules() {
        FlydbConfiguration c = FlydbConfiguration.builder().url("jdbc:x")
                .directoryGlob("mysql/param/**").fileRegex("^V.*\\.sql$")
                .migrationOrder("directory-version").build();

        assertThat(c.directoryGlob()).isEqualTo("mysql/param/**");
        assertThat(c.fileRegex()).isEqualTo("^V.*\\.sql$");
        assertThat(c.migrationOrder()).isEqualTo(MigrationOrder.DIRECTORY_VERSION);
        assertThat(c.directoryVersionRegex()).contains("?<version>");
    }

    @Test
    void glob_and_regex_cannot_compete_on_the_same_path_dimension() {
        assertThatThrownBy(() -> FlydbConfiguration.builder().url("jdbc:x")
                .directoryGlob("mysql/**").directoryRegex("^mysql/.*$").build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MISSING_REQUIRED_CONFIG);
    }

    @Test
    void invalid_path_regex_fails_during_configuration_build() {
        assertThatThrownBy(() -> FlydbConfiguration.builder().url("jdbc:x")
                .fileRegex("[").build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MISSING_REQUIRED_CONFIG);
    }

    @Test
    void family_selection_is_explicit_and_can_use_directory_versions() {
        FlydbConfiguration c = FlydbConfiguration.builder().url("jdbc:x")
                .versionSelection("family").versionSource("directory")
                .targetVersion("20230531").build();

        assertThat(c.versionSelection().mode()).isEqualTo(VersionSelection.Mode.FAMILY);
        assertThat(c.versionSelection().source()).isEqualTo(VersionSource.DIRECTORY);
        assertThat(c.versionSelection().target())
                .isEqualTo(MigrationVersion.parse("20230531"));
    }

    @Test
    void regex_selection_requires_a_valid_expression_and_no_version_boundaries() {
        assertThatThrownBy(() -> FlydbConfiguration.builder().url("jdbc:x")
                .versionSelection("regex").versionRegex("[").build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MISSING_REQUIRED_CONFIG);

        assertThatThrownBy(() -> FlydbConfiguration.builder().url("jdbc:x")
                .versionSelection("regex").versionRegex("^2023.*$")
                .targetVersion("20230531").build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERSION);
    }

    @Test
    void start_and_end_versions_define_an_inclusive_range() {
        FlydbConfiguration c = FlydbConfiguration.builder()
                .url("jdbc:x").startVersion("2").endVersion("4.1").build();

        assertThat(c.targetVersion()).isNull();
        assertThat(c.startVersion()).isEqualTo(MigrationVersion.parse("2"));
        assertThat(c.endVersion()).isEqualTo(MigrationVersion.parse("4.1"));
    }

    @Test
    void exact_version_cannot_be_combined_with_a_range() {
        assertThatThrownBy(() -> FlydbConfiguration.builder().url("jdbc:x")
                .targetVersion("2").startVersion("1").build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERSION);
    }

    @Test
    void start_version_cannot_be_higher_than_end_version() {
        assertThatThrownBy(() -> FlydbConfiguration.builder().url("jdbc:x")
                .startVersion("5").endVersion("2").build())
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERSION);
    }

    @Test
    void locations_are_defensively_copied_and_unmodifiable() {
        FlydbConfiguration c = FlydbConfiguration.builder()
                .url("jdbc:x").locations("filesystem:db/migration", "classpath:other").build();

        assertThat(c.locations()).containsExactly("filesystem:db/migration", "classpath:other");
        assertThatThrownBy(() -> c.locations().add("evil"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void placeholders_are_defensively_copied_and_unmodifiable() {
        Map<String, String> input = new HashMap<String, String>();
        input.put("k", "v");

        FlydbConfiguration c = FlydbConfiguration.builder().url("jdbc:x").placeholders(input).build();

        input.put("mutated", "after"); // 构造后改动入参不应影响配置
        assertThat(c.placeholders()).containsOnly(entry("k", "v"));
        assertThatThrownBy(() -> c.placeholders().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void migration_naming_convention_is_configurable() {
        FlydbConfiguration c = FlydbConfiguration.builder().url("jdbc:x")
                .sqlMigrationPrefix("M").repeatableMigrationPrefix("Q")
                .undoMigrationPrefix("D").sqlMigrationSeparator("--")
                .sqlMigrationSuffix(".ddl").build();

        assertThat(c.sqlMigrationPrefix()).isEqualTo("M");
        assertThat(c.repeatableMigrationPrefix()).isEqualTo("Q");
        assertThat(c.undoMigrationPrefix()).isEqualTo("D");
        assertThat(c.sqlMigrationSeparator()).isEqualTo("--");
        assertThat(c.sqlMigrationSuffix()).isEqualTo(".ddl");
    }

    /** DataSource 测试桩：仅用于按引用比对，不实现真实连接语义。 */
    private static final class StubDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String username, String password) throws SQLException { throw new UnsupportedOperationException(); }
        @Override public java.io.PrintWriter getLogWriter() throws SQLException { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(java.io.PrintWriter out) throws SQLException { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() throws SQLException { throw new UnsupportedOperationException(); }
        @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { throw new UnsupportedOperationException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { throw new UnsupportedOperationException(); }
    }
}
