package com.flydb.integration;

import javax.sql.DataSource;

/** 阶段 5 各数据库共用的集成测试接缝。 */
interface DatabaseTestSupport extends AutoCloseable {

    DataSource dataSource();

    String jdbcUrl();

    void resetSchema();

    @Override
    void close();
}
