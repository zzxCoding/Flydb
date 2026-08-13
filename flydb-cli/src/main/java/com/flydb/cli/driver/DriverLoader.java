package com.flydb.cli.driver;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.flydb.cli.config.CliConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 扫描 drivers/*.jar、实例化 JDBC Driver 并创建直接连接 DataSource。 */
public final class DriverLoader {

    public DriverContext open(Path driversDirectory, CliConfiguration configuration) {
        String driverClass = configuration.driver();
        if (driverClass == null || driverClass.isEmpty()) {
            driverClass = inferDriverClass(configuration.url());
        }
        URLClassLoader classLoader = new URLClassLoader(jarUrls(driversDirectory),
                contextClassLoader());
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
                    "无法从 " + driversDirectory + " 加载 " + driverClass
                            + ": " + e.getMessage(), e);
        }
    }

    private static URL[] jarUrls(Path directory) {
        List<Path> jars = new ArrayList<Path>();
        if (Files.isDirectory(directory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path path : stream) jars.add(path);
            } catch (java.io.IOException e) {
                throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                        "扫描 drivers 目录失败: " + directory + ": " + e.getMessage(), e);
            }
        }
        Collections.sort(jars);
        URL[] urls = new URL[jars.size()];
        try {
            for (int i = 0; i < jars.size(); i++) urls[i] = jars.get(i).toUri().toURL();
            return urls;
        } catch (MalformedURLException e) {
            throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                    "JDBC 驱动路径无效: " + e.getMessage(), e);
        }
    }

    private static String inferDriverClass(String url) {
        if (url == null) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, "必须提供 flydb.url");
        }
        if (url.startsWith("jdbc:mysql:")) return "com.mysql.cj.jdbc.Driver";
        if (url.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
        if (url.startsWith("jdbc:oracle:")) return "oracle.jdbc.OracleDriver";
        if (url.startsWith("jdbc:opengauss:")) return "org.opengauss.Driver";
        if (url.startsWith("jdbc:kingbase8:")) return "com.kingbase8.Driver";
        if (url.startsWith("jdbc:dm:")) return "dm.jdbc.driver.DmDriver";
        if (url.startsWith("jdbc:oceanbase:")) return "com.oceanbase.jdbc.Driver";
        throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                "无法按 JDBC URL 推断驱动类，请使用 --driver: " + redact(url));
    }

    private static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : DriverLoader.class.getClassLoader();
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (java.io.IOException ignored) {
            // 保留原始加载异常。
        }
    }

    private static String redact(String url) {
        return url.replaceAll("(?i)(//[^/:@]+:)[^@]+@", "$1****@");
    }
}
