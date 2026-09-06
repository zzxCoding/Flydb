package com.flydb.runtime;

import java.nio.file.Path;
import com.flydb.core.Flydb;
import com.flydb.core.api.ExecutionObserver;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.runtime.config.CliConfiguration;
import com.flydb.runtime.driver.DriverContext;
import com.flydb.runtime.driver.DriverLoader;

/** Per-invocation driver and classloader lifetime, independent of CLI and HTTP. */
public final class RuntimeSession implements AutoCloseable {
    private final DriverContext driver;
    private final ClassLoader previous;
    private final Flydb flydb;
    private final CliConfiguration configuration;

    public RuntimeSession(CliConfiguration configuration, Path drivers, Path working,
                          ExecutionObserver observer) {
        this.configuration = configuration.inDirectory(working);
        if (configuration.url() == null || configuration.url().trim().isEmpty())
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, "必须提供 flydb.url");
        this.driver = new DriverLoader().open(drivers, this.configuration);
        this.previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(driver.classLoader());
        try {
            this.flydb = new Flydb(this.configuration.toCoreConfiguration(
                    driver.dataSource(), driver.classLoader(), observer));
        } catch (RuntimeException e) {
            Thread.currentThread().setContextClassLoader(previous);
            driver.close(); throw e;
        }
    }
    public Flydb flydb() { return flydb; }
    public CliConfiguration configuration() { return configuration; }
    public String driverClass() { return driver.driverClass(); }
    public String driverSource() { return driver.source(); }
    @Override public void close() {
        try { driver.close(); }
        finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
