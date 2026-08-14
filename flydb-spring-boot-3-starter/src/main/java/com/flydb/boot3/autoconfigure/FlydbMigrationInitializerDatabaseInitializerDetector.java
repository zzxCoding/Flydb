package com.flydb.boot3.autoconfigure;

import java.util.Collections;
import java.util.Set;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

/** 把 Flydb 初始化器接入 Spring Boot 官方数据库初始化依赖编排。 */
public class FlydbMigrationInitializerDatabaseInitializerDetector
        extends AbstractBeansOfTypeDatabaseInitializerDetector {

    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Collections.<Class<?>>singleton(FlydbMigrationInitializer.class);
    }
}
