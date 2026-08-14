package com.flydb.boot3.autoconfigure;

import com.flydb.core.Flydb;
import org.springframework.beans.factory.InitializingBean;

/** 在 Spring bean 初始化阶段执行迁移，失败时直接中止应用启动。 */
public class FlydbMigrationInitializer implements InitializingBean {

    private final Flydb flydb;

    public FlydbMigrationInitializer(Flydb flydb) {
        this.flydb = flydb;
    }

    @Override
    public void afterPropertiesSet() {
        flydb.migrate();
    }
}
