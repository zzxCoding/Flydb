package com.flydb.boot3.autoconfigure;

import javax.sql.DataSource;

import com.flydb.core.Flydb;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/** Spring Boot 3 自动装配入口。 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(Flydb.class)
@ConditionalOnProperty(prefix = "flydb", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlydbProperties.class)
@Import(DatabaseInitializationDependencyConfigurer.class)
public class FlydbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FlydbSlf4jLogBridge flydbSlf4jLogBridge() {
        return new FlydbSlf4jLogBridge();
    }

    @Bean
    @ConditionalOnMissingBean
    public Flydb flydb(FlydbProperties properties, ObjectProvider<DataSource> dataSources) {
        DataSource dataSource = migrationDataSource(properties, dataSources);
        ClassLoader classLoader = ClassUtils.getDefaultClassLoader();
        return new Flydb(properties.toCoreConfiguration(dataSource, classLoader));
    }

    @Bean
    @ConditionalOnMissingBean
    public FlydbMigrationInitializer flydbMigrationInitializer(
            Flydb flydb, FlydbSlf4jLogBridge logBridge) {
        return new FlydbMigrationInitializer(flydb);
    }

    private DataSource migrationDataSource(FlydbProperties properties,
                                           ObjectProvider<DataSource> dataSources) {
        if (StringUtils.hasText(properties.getUrl())) {
            DriverManagerDataSource dedicated = new DriverManagerDataSource();
            dedicated.setUrl(properties.getUrl());
            if (StringUtils.hasText(properties.getUser())) dedicated.setUsername(properties.getUser());
            if (properties.getPassword() != null) dedicated.setPassword(properties.getPassword());
            if (StringUtils.hasText(properties.getDriver())) dedicated.setDriverClassName(properties.getDriver());
            return dedicated;
        }
        DataSource applicationDataSource = dataSources.getIfAvailable();
        if (applicationDataSource == null) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "必须提供应用 DataSource 或 flydb.url");
        }
        return applicationDataSource;
    }
}
