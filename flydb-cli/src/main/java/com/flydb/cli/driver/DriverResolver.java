package com.flydb.cli.driver;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.flydb.cli.config.CliConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 在显式目录、运行时及 Maven 本地仓库之间解析 JDBC 驱动。 */
public final class DriverResolver {

    public ResolvedDriver resolve(Path driversDirectory, CliConfiguration configuration) {
        DriverDescriptor descriptor = DriverDescriptor.from(configuration);
        MavenSettings settings = MavenSettings.load(configuration.mavenSettings());
        URL[] explicit = jarUrls(driversDirectory);
        if (containsDriver(descriptor.driverClass, explicit)) {
            return new ResolvedDriver(descriptor.driverClass, explicit, "drivers 目录");
        }
        if (containsDriver(descriptor.driverClass, new URL[0])) {
            return new ResolvedDriver(descriptor.driverClass, explicit, "运行时 classpath");
        }
        if (descriptor.coordinate != null) {
            Path repository = localRepository(configuration, settings);
            Path artifact = descriptor.coordinate.jar(repository);
            if (Files.isRegularFile(artifact)) {
                URL[] urls = append(explicit, toUrl(artifact));
                if (containsDriver(descriptor.driverClass, urls)) {
                    return new ResolvedDriver(descriptor.driverClass, urls, "Maven 本地仓库");
                }
            }
            Path cache = driverCache(configuration);
            Path cachedArtifact = descriptor.coordinate.jar(cache);
            if (Files.isRegularFile(cachedArtifact)) {
                URL[] urls = append(explicit, toUrl(cachedArtifact));
                if (containsDriver(descriptor.driverClass, urls)) {
                    return new ResolvedDriver(descriptor.driverClass, urls, "Flydb 驱动缓存");
                }
            }
            if (!configuration.offline() && downloadEnabled(configuration)) {
                List<String> failures = new ArrayList<String>();
                for (MavenSettings.Repository remote : settings.effectiveRepositories()) {
                    try {
                        new ArtifactDownloader().download(remote,
                                descriptor.coordinate.repositoryPath(), cachedArtifact);
                        URL[] urls = append(explicit, toUrl(cachedArtifact));
                        if (containsDriver(descriptor.driverClass, urls)) {
                            return new ResolvedDriver(descriptor.driverClass, urls,
                                    "Maven " + ("central".equals(remote.id)
                                            ? "中央仓库" : "私服 " + remote.id));
                        }
                        Files.deleteIfExists(cachedArtifact);
                        failures.add(remote.id + ": 下载的 JAR 不包含 "
                                + descriptor.driverClass);
                    } catch (Exception e) {
                        failures.add(remote.id + ": " + safeMessage(e));
                    }
                }
                throw notFound(descriptor, driversDirectory, configuration, settings, failures);
            }
        }
        throw notFound(descriptor, driversDirectory, configuration, settings,
                Collections.<String>emptyList());
    }

    private static Path localRepository(CliConfiguration configuration, MavenSettings settings) {
        if (notBlank(configuration.mavenLocalRepository())) {
            return Paths.get(configuration.mavenLocalRepository()).toAbsolutePath().normalize();
        }
        String system = System.getProperty("maven.repo.local");
        if (notBlank(system)) return Paths.get(system).toAbsolutePath().normalize();
        if (settings.localRepository() != null) return settings.localRepository();
        return Paths.get(System.getProperty("user.home"), ".m2", "repository");
    }

    private static Path driverCache(CliConfiguration configuration) {
        if (notBlank(configuration.driverCache())) {
            return Paths.get(configuration.driverCache()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".flydb", "drivers");
    }

    private static boolean downloadEnabled(CliConfiguration configuration) {
        String mode = configuration.driverDownload();
        if ("auto".equalsIgnoreCase(mode)) return true;
        if ("never".equalsIgnoreCase(mode)) return false;
        throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                "flydb.driver-download 仅支持 auto 或 never");
    }

    private static boolean containsDriver(String driverClass, URL[] urls) {
        URLClassLoader loader = new URLClassLoader(urls, contextClassLoader());
        try {
            Class.forName(driverClass, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } finally {
            try { loader.close(); } catch (java.io.IOException ignored) { }
        }
    }

    static URL[] jarUrls(Path directory) {
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
        for (int i = 0; i < jars.size(); i++) urls[i] = toUrl(jars.get(i));
        return urls;
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                    "JDBC 驱动路径无效: " + path, e);
        }
    }

    private static URL[] append(URL[] existing, URL additional) {
        URL[] result = new URL[existing.length + 1];
        System.arraycopy(existing, 0, result, 0, existing.length);
        result[existing.length] = additional;
        return result;
    }

    private static FlydbException notFound(DriverDescriptor descriptor, Path drivers,
                                           CliConfiguration configuration,
                                           MavenSettings settings, List<String> failures) {
        StringBuilder detail = new StringBuilder("无法加载 ").append(descriptor.driverClass)
                .append("\n已检查:\n- ").append(drivers)
                .append("\n- 运行时 classpath");
        if (descriptor.coordinate != null) {
            detail.append("\n- Maven 本地仓库: ")
                    .append(localRepository(configuration, settings))
                    .append(" (坐标 ").append(descriptor.coordinate).append(')')
                    .append("\n- Flydb 驱动缓存: ").append(driverCache(configuration))
                    .append("\n- Maven settings: ").append(settings.source());
            if (configuration.offline()) detail.append("\n远程解析已禁用: flydb.offline=true");
            else if (!downloadEnabled(configuration)) {
                detail.append("\n远程解析已禁用: flydb.driver-download=never");
            }
            if (!failures.isEmpty()) {
                detail.append("\n远程解析失败:");
                for (String failure : failures) detail.append("\n- ").append(failure);
            }
        } else {
            detail.append("\n未配置可解析的驱动坐标；小众数据库请设置 flydb.driver-coordinate");
        }
        return new FlydbException(ErrorCode.DRIVER_NOT_FOUND, detail.toString());
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }

    static ClassLoader contextClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : DriverResolver.class.getClassLoader();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class DriverDescriptor {
        private final String driverClass;
        private final Coordinate coordinate;

        private DriverDescriptor(String driverClass, Coordinate coordinate) {
            this.driverClass = driverClass;
            this.coordinate = coordinate;
        }

        static DriverDescriptor from(CliConfiguration configuration) {
            String driverClass = configuration.driver();
            Coordinate coordinate = Coordinate.parse(configuration.driverCoordinate());
            String url = configuration.url();
            if (url == null) throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "必须提供 flydb.url");
            DriverDescriptor catalog = catalog(url);
            if (!notBlank(driverClass)) {
                if (catalog == null) {
                    throw new FlydbException(ErrorCode.DRIVER_NOT_FOUND,
                            "无法按 JDBC URL 推断驱动类，请使用 --driver: " + redact(url));
                }
                driverClass = catalog.driverClass;
            }
            if (coordinate == null && catalog != null) coordinate = catalog.coordinate;
            return new DriverDescriptor(driverClass, coordinate);
        }

        private static DriverDescriptor catalog(String url) {
            if (url.startsWith("jdbc:mysql:")) return descriptor("com.mysql.cj.jdbc.Driver",
                    "com.mysql:mysql-connector-j:8.2.0");
            if (url.startsWith("jdbc:postgresql:")) return descriptor("org.postgresql.Driver",
                    "org.postgresql:postgresql:42.7.4");
            if (url.startsWith("jdbc:oracle:")) return descriptor("oracle.jdbc.OracleDriver",
                    "com.oracle.database.jdbc:ojdbc8:21.5.0.0");
            if (url.startsWith("jdbc:oceanbase:")) return descriptor(
                    "com.oceanbase.jdbc.Driver", "com.oceanbase:oceanbase-client:2.4.0");
            if (url.startsWith("jdbc:opengauss:")) return descriptor("org.opengauss.Driver", null);
            if (url.startsWith("jdbc:kingbase8:")) return descriptor("com.kingbase8.Driver", null);
            if (url.startsWith("jdbc:dm:")) return descriptor("dm.jdbc.driver.DmDriver", null);
            return null;
        }

        private static DriverDescriptor descriptor(String driverClass, String coordinate) {
            return new DriverDescriptor(driverClass, Coordinate.parse(coordinate));
        }

        private static String redact(String url) {
            return url.replaceAll("(?i)(//[^/:@]+:)[^@]+@", "$1****@");
        }
    }

    static final class Coordinate {
        private final String groupId;
        private final String artifactId;
        private final String version;

        private Coordinate(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        static Coordinate parse(String value) {
            if (!notBlank(value)) return null;
            String[] parts = value.trim().split(":");
            if (parts.length != 3 || !notBlank(parts[0]) || !notBlank(parts[1])
                    || !notBlank(parts[2])) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.driver-coordinate 必须是 groupId:artifactId:version");
            }
            return new Coordinate(parts[0], parts[1], parts[2]);
        }

        Path jar(Path repository) {
            return repository.resolve(groupId.replace('.', '/')).resolve(artifactId)
                    .resolve(version).resolve(artifactId + "-" + version + ".jar");
        }

        String repositoryPath() {
            return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                    + artifactId + "-" + version + ".jar";
        }

        @Override public String toString() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }
}
