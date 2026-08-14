package com.flydb.cli.driver;

import java.io.IOException;
import java.net.URLClassLoader;

import javax.sql.DataSource;

/** 动态 JDBC 驱动的 DataSource 与子类加载器生命周期。 */
public final class DriverContext implements AutoCloseable {

    private final DataSource dataSource;
    private final URLClassLoader classLoader;

    DriverContext(DataSource dataSource, URLClassLoader classLoader) {
        this.dataSource = dataSource;
        this.classLoader = classLoader;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable) {
            try {
                ((AutoCloseable) dataSource).close();
            } catch (Exception ignored) {
                // 继续关闭类加载器。
            }
        }
        try {
            classLoader.close();
        } catch (IOException ignored) {
            // 命令结果或主异常优先。
        }
    }
}
