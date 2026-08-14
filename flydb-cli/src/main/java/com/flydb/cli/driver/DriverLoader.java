package com.flydb.cli.driver;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Driver;

import com.flydb.cli.config.CliConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 扫描 drivers/*.jar、实例化 JDBC Driver 并创建直接连接 DataSource。 */
public final class DriverLoader {

    public DriverContext open(Path driversDirectory, CliConfiguration configuration) {
        ResolvedDriver resolved = new DriverResolver().resolve(driversDirectory, configuration);
        String driverClass = resolved.driverClass();
        URLClassLoader classLoader = new URLClassLoader(resolved.urls(),
                DriverResolver.contextClassLoader());
        try {
            Class<?> type = Class.forName(driverClass, true, classLoader);
            Object instance = type.newInstance();
            if (!(instance instanceof Driver)) {
                throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                        "类不是 java.sql.Driver: " + driverClass);
            }
            return new DriverContext(new DriverDataSource((Driver) instance,
                    configuration.url(), configuration.user(), configuration.password()),
                    classLoader);
        } catch (FlydbException e) {
            closeQuietly(classLoader);
            throw e;
        } catch (Exception e) {
            closeQuietly(classLoader);
            throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                    "已解析驱动但无法加载 " + driverClass
                            + ": " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (java.io.IOException ignored) {
            // 保留原始加载异常。
        }
    }
}
