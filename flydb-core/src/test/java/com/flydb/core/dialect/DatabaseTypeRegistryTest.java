package com.flydb.core.dialect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DatabaseTypeRegistry 单测（设计 03 §1、08 §1）。
 *
 * <p>覆盖：URL 前缀优先于产品名（mock Connection 模拟达梦 compatibleMode=oracle 伪装场景）、
 * TiDB 与 MySQL 歧义消解、显式 databaseType 跳过探测、零候选报错。
 */
@DisplayName("DatabaseTypeRegistry")
class DatabaseTypeRegistryTest {

    @Nested
    @DisplayName("显式指定跳过探测")
    class ExplicitDatabaseType {

        @Test
        @DisplayName("配置了 databaseType 时按 name() 直接命中，跳过全部探测")
        void explicitTypeBypassesDetection() {
            DatabaseTypeRegistry registry = new DatabaseTypeRegistry(new DatabaseType[]{
                    new FakeDatabaseType("mysql", 0, "jdbc:mysql://", "MySQL"),
                    new FakeDatabaseType("pg", 1, "jdbc:postgresql://", "PostgreSQL"),
            });
            DatabaseType result = registry.detect("jdbc:mysql://localhost:3306/test", null, "pg");
            assertThat(result.name()).isEqualTo("pg");
        }

        @Test
        @DisplayName("显式指定的类型不存在 → FLYDB-1002")
        void explicitTypeNotExistsErrors() {
            DatabaseTypeRegistry registry = new DatabaseTypeRegistry(new DatabaseType[]{});
            assertThatThrownBy(() -> registry.detect("jdbc:mysql://localhost:3306/test", null, "nonexistent"))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> assertThat(((FlydbException) ex).errorCode())
                            .isEqualTo(ErrorCode.UNRECOGNIZED_DATABASE_TYPE));
        }
    }

    @Nested
    @DisplayName("URL 前缀优先于产品名")
    class UrlPrefixOverridesProductName {

        @Test
        @DisplayName("达梦带 compatibleMode=oracle 时按 URL 前缀 jdbc:dm:// 判定")
        void dmWithOracleCompatibleMode() {
            DatabaseType dm = new FakeDatabaseType("dm", 0, "jdbc:dm://", "DM DBMS");
            DatabaseType oracle = new FakeDatabaseType("oracle", 0, "jdbc:oracle://", "Oracle");
            DatabaseTypeRegistry registry = new DatabaseTypeRegistry(new DatabaseType[]{dm, oracle});

            // 模拟达梦 compatibleMode=oracle：产品名返回 "Oracle" 但 URL 是 jdbc:dm://
            Connection fakeConn = mockConnection("Oracle");
            DatabaseType result = registry.detect("jdbc:dm://localhost:5236", fakeConn, null);
            assertThat(result.name()).isEqualTo("dm"); // URL 前缀优先，不被产品名 Oracle 推翻
        }

        @Test
        @DisplayName("同前缀时阶段二（产品名）区分 TiDB 与 MySQL")
        void samePrefixDisambiguatedByProductName() {
            DatabaseType tidb = new FakeDatabaseType("tidb", 10, "jdbc:mysql://", "TiDB");
            DatabaseType mysql = new FakeDatabaseType("mysql", 5, "jdbc:mysql://", "MySQL");
            DatabaseTypeRegistry registry = new DatabaseTypeRegistry(new DatabaseType[]{tidb, mysql});

            Connection tidbConn = mockConnection("TiDB");
            DatabaseType result = registry.detect("jdbc:mysql://localhost:4000", tidbConn, null);
            assertThat(result.name()).isEqualTo("tidb");

            Connection mysqlConn = mockConnection("MySQL");
            result = registry.detect("jdbc:mysql://localhost:3306", mysqlConn, null);
            assertThat(result.name()).isEqualTo("mysql");
        }
    }

    @Nested
    @DisplayName("零候选与歧义")
    class NoCandidateOrAmbiguity {

        @Test
        @DisplayName("零候选 → FLYDB-1002")
        void noCandidateErrors() {
            DatabaseTypeRegistry registry = new DatabaseTypeRegistry(new DatabaseType[]{});
            assertThatThrownBy(() -> registry.detect("jdbc:unknown://host:1234/db", null, null))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> assertThat(((FlydbException) ex).errorCode())
                            .isEqualTo(ErrorCode.UNRECOGNIZED_DATABASE_TYPE));
        }

        @Test
        @DisplayName("歧义 → 报错而非猜测")
        void ambiguousAfterStageTwoErrors() {
            DatabaseType a = new FakeDatabaseType("a", 0, "jdbc:same://", "SameProduct");
            DatabaseType b = new FakeDatabaseType("b", 0, "jdbc:same://", "SameProduct");
            DatabaseTypeRegistry registry = new DatabaseTypeRegistry(new DatabaseType[]{a, b});
            Connection conn = mockConnection("SameProduct");
            assertThatThrownBy(() -> registry.detect("jdbc:same://host", conn, null))
                    .isInstanceOf(FlydbException.class)
                    .hasMessageContaining("jdbc:same://");
        }
    }

    // ---- 桩 ----

    private static Connection mockConnection(final String productName) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("getMetaData".equals(name)) {
                            return Proxy.newProxyInstance(
                                    java.sql.DatabaseMetaData.class.getClassLoader(),
                                    new Class<?>[]{java.sql.DatabaseMetaData.class},
                                    (mdProxy, mdMethod, mdArgs) -> {
                                        if ("getDatabaseProductName".equals(mdMethod.getName())) {
                                            return productName;
                                        }
                                        return defaultValue(mdMethod.getReturnType());
                                    });
                        }
                        if ("close".equals(name) || "commit".equals(name) || "rollback".equals(name)) {
                            return null;
                        }
                        if ("isClosed".equals(name) || "getAutoCommit".equals(name) || "isReadOnly".equals(name)) {
                            return false;
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == char.class) return '\0';
        return null;
    }

    private static final class FakeDatabaseType implements DatabaseType {
        private final String name;
        private final int priority;
        private final String urlPrefix;
        private final String productName;

        FakeDatabaseType(String name, int priority, String urlPrefix, String productName) {
            this.name = name;
            this.priority = priority;
            this.urlPrefix = urlPrefix;
            this.productName = productName;
        }

        @Override
        public String name() { return name; }
        @Override
        public int priority() { return priority; }
        @Override
        public boolean handlesUrl(String jdbcUrl) { return jdbcUrl != null && jdbcUrl.startsWith(urlPrefix); }
        @Override
        public boolean handlesConnection(Connection connection) throws SQLException {
            return productName.equals(connection.getMetaData().getDatabaseProductName());
        }
        @Override
        public Database createDatabase(Connection connection, FlydbConfiguration cfg) {
            return null;
        }
    }
}