package com.flydb.integration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.dialect.Database;
import com.flydb.core.dialect.DatabaseTypeRegistry;
import com.flydb.core.dialect.OpenGaussDatabaseType;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.lock.MigrationLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 外部实例契约；默认只读，锁竞争测试需要独立开关，不自动下载驱动。 */
@DisplayName("真实 openGauss 契约")
class OpenGaussDatabaseContractTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "FLYDB_TEST_OPENGAUSS_URL", matches = ".+")
    @DisplayName("真实实例支持方言探测、Schema、JDBC 元数据和 bigint advisory lock 函数")
    void openGaussRealInstanceReadOnlyContract() throws Exception {
        String url = environment("FLYDB_TEST_OPENGAUSS_URL");
        DataSource dataSource = dataSource(url);
        FlydbConfiguration configuration = FlydbConfiguration.builder()
                .dataSource(dataSource).databaseType("opengauss").build();
        OpenGaussDatabaseType type = new OpenGaussDatabaseType();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(type.handlesUrl(url)).isTrue();
            assertThat(type.handlesConnection(connection)).isTrue();
            assertThat(new DatabaseTypeRegistry().detect(url, connection, null).name())
                    .isEqualTo("opengauss");
            try (Database database = type.createDatabase(connection, configuration)) {
                String schema = database.currentSchema();
                assertThat(schema).isNotBlank();
                assertThat(database.currentUser()).isNotBlank();
                try (ResultSet tables = connection.getMetaData()
                        .getTables(null, schema, "%", new String[] {"TABLE"})) {
                    while (tables.next()) {
                        assertThat(tables.getString("TABLE_SCHEM")).isEqualTo(schema);
                    }
                }
                List<String> functions = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT proname FROM pg_proc WHERE proname IN "
                                     + "('pg_advisory_lock', 'pg_advisory_unlock') "
                                     + "AND oidvectortypes(proargtypes) = 'bigint'")) {
                    while (rows.next()) {
                        functions.add(rows.getString(1));
                    }
                }
                assertThat(functions).contains("pg_advisory_lock", "pg_advisory_unlock");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FLYDB_TEST_OPENGAUSS_URL", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "FLYDB_TEST_OPENGAUSS_LOCK_TESTS", matches = "true")
    @DisplayName("显式授权后验证会话锁互斥、超时与释放后重新获取")
    void openGaussRealInstanceLockContract() throws Exception {
        DataSource dataSource = dataSource(environment("FLYDB_TEST_OPENGAUSS_URL"));
        // 此名称仅生成独立 advisory key，不创建对应表，也不使用业务迁移的锁。
        FlydbConfiguration configuration = FlydbConfiguration.builder()
                .dataSource(dataSource).databaseType("opengauss")
                .table("flydb_opengauss_lock_contract").lockTimeoutSeconds(1).build();
        try (Connection connection = dataSource.getConnection();
             Database database = new OpenGaussDatabaseType().createDatabase(connection, configuration);
             MigrationLock owner = database.createLock(configuration);
             MigrationLock contender = database.createLock(configuration)) {
            owner.acquire();
            FlydbException failure = assertThrows(FlydbException.class, contender::acquire);
            assertThat(failure.errorCode()).isEqualTo(ErrorCode.LOCK_ACQUISITION_TIMEOUT);
            assertThat(failure.getCause()).isInstanceOf(SQLException.class);
            assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo("57014");
            owner.release();
            contender.acquire();
            contender.release();
        }
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(url,
                environment("FLYDB_TEST_OPENGAUSS_USER"),
                environment("FLYDB_TEST_OPENGAUSS_PASSWORD"));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("已设置 openGauss URL，但缺少环境变量 " + name);
        }
        return value;
    }
}
