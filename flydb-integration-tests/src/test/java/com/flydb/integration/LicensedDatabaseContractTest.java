package com.flydb.integration;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.dialect.Database;
import com.flydb.core.dialect.DmDatabase;
import com.flydb.core.dialect.DmDatabaseType;
import com.flydb.core.dialect.KingbaseESDatabaseType;
import com.flydb.core.dialect.OracleDatabaseType;
import com.flydb.core.lock.MigrationLock;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅在用户提供授权数据库连接信息时运行，不拉取镜像或捆绑专有驱动。 */
@DisplayName("阶段 5 授权数据库只读契约")
class LicensedDatabaseContractTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "FLYDB_TEST_KINGBASE_URL", matches = ".+")
    @DisplayName("真实 KingbaseES 实例可探测方言、Schema 与锁能力")
    void kingbaseRealInstanceContract() throws Exception {
        String url = environment("FLYDB_TEST_KINGBASE_URL");
        DataSource dataSource = dataSource("KINGBASE", url);
        FlydbConfiguration configuration = FlydbConfiguration.builder()
                .dataSource(dataSource).databaseType("kingbasees").build();
        KingbaseESDatabaseType type = new KingbaseESDatabaseType();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(type.handlesUrl(url)).isTrue();
            assertThat(type.handlesConnection(connection)).isTrue();
            try (Database database = type.createDatabase(connection, configuration)) {
                assertThat(database.currentSchema()).isNotBlank();
                assertThat(database.currentUser()).isNotBlank();
                try (MigrationLock lock = database.createLock(configuration)) {
                    assertThat(lock).isNotNull();
                }
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FLYDB_TEST_DM_URL", matches = ".+")
    @DisplayName("真实达梦实例可探测方言、Schema 与大小写模式")
    void dmRealInstanceContract() throws Exception {
        String url = environment("FLYDB_TEST_DM_URL");
        DataSource dataSource = dataSource("DM", url);
        FlydbConfiguration configuration = FlydbConfiguration.builder()
                .dataSource(dataSource).databaseType("dm").build();
        DmDatabaseType type = new DmDatabaseType();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(type.handlesUrl(url)).isTrue();
            assertThat(type.handlesConnection(connection)).isTrue();
            try (DmDatabase database = (DmDatabase) type.createDatabase(connection, configuration)) {
                assertThat(database.currentSchema()).isNotBlank();
                assertThat(database.currentUser()).isNotBlank();
                String historyDdl = database.schemaHistoryDdl()
                        .createTableSql("flydb_schema_history");
                assertThat(historyDdl.startsWith("CREATE TABLE \"flydb_schema_history\""))
                        .isEqualTo(database.caseSensitive());
                try (MigrationLock lock = database.createLock(configuration)) {
                    assertThat(lock).isNotNull();
                }
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FLYDB_TEST_ORACLE_URL", matches = ".+")
    @DisplayName("真实 Oracle 实例可探测官方方言、Schema 与锁能力")
    void oracleRealInstanceContract() throws Exception {
        String url = environment("FLYDB_TEST_ORACLE_URL");
        DataSource dataSource = dataSource("ORACLE", url);
        FlydbConfiguration configuration = FlydbConfiguration.builder()
                .dataSource(dataSource).databaseType("oracle").build();
        OracleDatabaseType type = new OracleDatabaseType();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(type.handlesUrl(url)).isTrue();
            assertThat(type.handlesConnection(connection)).isTrue();
            try (Database database = type.createDatabase(connection, configuration)) {
                assertThat(database.currentSchema()).isNotBlank();
                assertThat(database.currentUser()).isNotBlank();
                assertThat(database.supportsDdlTransactions()).isFalse();
                try (MigrationLock lock = database.createLock(configuration)) {
                    assertThat(lock).isNotNull();
                }
            }
        }
    }

    private static DataSource dataSource(String database, String url) {
        return new DriverManagerDataSource(url,
                environment("FLYDB_TEST_" + database + "_USER"),
                environment("FLYDB_TEST_" + database + "_PASSWORD"));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null) {
            throw new IllegalStateException("已设置数据库 URL，但缺少环境变量 " + name);
        }
        return value;
    }
}
