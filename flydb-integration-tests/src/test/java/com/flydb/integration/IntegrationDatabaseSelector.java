package com.flydb.integration;

import java.util.Locale;

import org.junit.jupiter.api.Assumptions;

/**
 * 选择集成测试实际使用的数据库家族。
 *
 * <p>本地默认只启动 MySQL；CI 通过 {@code -Dflydb.integration.database=} 为
 * 每个方言矩阵项显式选择 MySQL 或 PostgreSQL 家族。设置为 {@code all} 可恢复
 * 同时运行两套基础家族契约。
 */
final class IntegrationDatabaseSelector {

    static final String PROPERTY = "flydb.integration.database";
    private static final String ALL = "all";

    private IntegrationDatabaseSelector() {
    }

    static boolean selected(String database) {
        String value = System.getProperty(PROPERTY, "mysql").trim().toLowerCase(Locale.ROOT);
        return ALL.equals(value) || database.equals(value);
    }

    static boolean mysqlFamilySelected() {
        return selected("mysql") || selected("tidb") || selected("oceanbase-mysql");
    }

    static boolean postgresFamilySelected() {
        return selected("postgresql") || selected("opengauss") || selected("kingbasees");
    }

    static void assume(String database) {
        Assumptions.assumeTrue(selected(database),
                () -> "集成测试选择器跳过 " + database + "（-D" + PROPERTY + "="
                        + System.getProperty(PROPERTY, "mysql") + "）");
    }

    static void assumeMysqlFamily() {
        Assumptions.assumeTrue(mysqlFamilySelected(),
                () -> "集成测试选择器跳过 MySQL 家族（-D" + PROPERTY + "="
                        + System.getProperty(PROPERTY, "mysql") + "）");
    }

    static void assumePostgresFamily() {
        Assumptions.assumeTrue(postgresFamilySelected(),
                () -> "集成测试选择器跳过 PostgreSQL 家族（-D" + PROPERTY + "="
                        + System.getProperty(PROPERTY, "mysql") + "）");
    }
}
