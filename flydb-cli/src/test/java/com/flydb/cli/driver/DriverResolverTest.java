package com.flydb.cli.driver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.cli.config.CliConfiguration;
import com.flydb.cli.config.ConfigLoader;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DriverResolver")
class DriverResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("直接使用 Maven 本地仓库中的 JDBC 驱动")
    void resolvesDriverFromConfiguredMavenLocalRepository() throws Exception {
        Path drivers = Files.createDirectories(temporaryDirectory.resolve("drivers"));
        Path dialectJar = drivers.resolve("custom-dialect.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dialectJar))) {
            output.putNextEntry(new JarEntry("META-INF/services/com.flydb.core.dialect.DatabaseType"));
            output.write("fixture.Dialect".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path repository = temporaryDirectory.resolve("repository");
        Path driverJar = repository.resolve("example/fixture-jdbc/1.0/fixture-jdbc-1.0.jar");
        Files.createDirectories(driverJar.getParent());
        createFakeDriverJar(driverJar);

        Map<String, String> cli = new HashMap<String, String>();
        cli.put("flydb.url", "jdbc:fixture:test");
        cli.put("flydb.driver", "fixture.IsolatedDriver");
        cli.put("flydb.driver-coordinate", "example:fixture-jdbc:1.0");
        cli.put("flydb.maven-local-repository", repository.toString());
        CliConfiguration configuration = new ConfigLoader().load(null, temporaryDirectory,
                temporaryDirectory, Collections.<String, String>emptyMap(), cli);

        ResolvedDriver resolved = new DriverResolver().resolve(drivers, configuration);

        assertThat(resolved.driverClass()).isEqualTo("fixture.IsolatedDriver");
        assertThat(resolved.urls()).containsExactly(dialectJar.toUri().toURL(),
                driverJar.toUri().toURL());
        assertThat(resolved.source()).isEqualTo("Maven 本地仓库");
    }

    @Test
    @DisplayName("读取 settings.xml 的 localRepository")
    void resolvesMavenLocalRepositoryFromSettings() throws Exception {
        Path repository = temporaryDirectory.resolve("company-local-repository");
        Path driverJar = repository.resolve("example/fixture-jdbc/1.0/fixture-jdbc-1.0.jar");
        Files.createDirectories(driverJar.getParent());
        createFakeDriverJar(driverJar);
        Path settings = temporaryDirectory.resolve("local-settings.xml");
        Files.write(settings, ("<settings><localRepository>" + repository
                + "</localRepository></settings>").getBytes(StandardCharsets.UTF_8));
        Map<String, String> cli = baseConfiguration();
        cli.put("flydb.maven-settings", settings.toString());

        ResolvedDriver resolved = new DriverResolver().resolve(
                temporaryDirectory.resolve("drivers"), configuration(cli));

        assertThat(resolved.urls()).containsExactly(driverJar.toUri().toURL());
        assertThat(resolved.source()).isEqualTo("Maven 本地仓库");
    }

    @Test
    @DisplayName("离线模式只给出本地解析轨迹且不访问远程仓库")
    void offlineModeStopsBeforeRemoteRepository() throws Exception {
        Path settings = temporaryDirectory.resolve("offline-settings.xml");
        Files.write(settings, ("<settings><mirrors><mirror><id>unreachable</id>"
                + "<url>http://127.0.0.1:1/repository</url><mirrorOf>*</mirrorOf>"
                + "</mirror></mirrors></settings>").getBytes(StandardCharsets.UTF_8));
        Map<String, String> cli = baseConfiguration();
        cli.put("flydb.maven-settings", settings.toString());
        cli.put("flydb.maven-local-repository",
                temporaryDirectory.resolve("empty-repository").toString());
        cli.put("flydb.driver-cache", temporaryDirectory.resolve("empty-cache").toString());
        cli.put("flydb.offline", "true");

        assertThatThrownBy(() -> new DriverResolver().resolve(
                temporaryDirectory.resolve("drivers"), configuration(cli)))
                .isInstanceOf(FlydbException.class)
                .hasMessageContaining("flydb.offline=true")
                .hasMessageContaining("Maven settings")
                .hasMessageNotContaining("Connection refused");
    }

    @Test
    @DisplayName("遵循 Maven mirror 和 server 认证从私服下载并缓存驱动")
    void downloadsFromAuthenticatedMavenMirrorAndCachesDriver() throws Exception {
        Path servedJar = temporaryDirectory.resolve("served/fixture-jdbc-1.0.jar");
        Files.createDirectories(servedJar.getParent());
        createFakeDriverJar(servedJar);
        final byte[] jarBytes = Files.readAllBytes(servedJar);
        final String expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                "alice:secret".getBytes(StandardCharsets.UTF_8));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repository/example/fixture-jdbc/1.0/fixture-jdbc-1.0.jar",
                exchange -> {
                    if (!expectedAuthorization.equals(exchange.getRequestHeaders()
                            .getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                    } else {
                        exchange.sendResponseHeaders(200, jarBytes.length);
                        exchange.getResponseBody().write(jarBytes);
                    }
                    exchange.close();
                });
        server.start();
        try {
            Path settings = temporaryDirectory.resolve("settings.xml");
            String mirrorUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/repository";
            Files.write(settings, ("<settings><mirrors><mirror><id>company-nexus</id>"
                    + "<url>" + mirrorUrl + "</url><mirrorOf>*</mirrorOf></mirror></mirrors>"
                    + "<servers><server><id>company-nexus</id><username>alice</username>"
                    + "<password>secret</password></server></servers></settings>")
                    .getBytes(StandardCharsets.UTF_8));
            Path cache = temporaryDirectory.resolve("cache");
            Map<String, String> cli = baseConfiguration();
            cli.put("flydb.maven-local-repository",
                    temporaryDirectory.resolve("empty-repository").toString());
            cli.put("flydb.maven-settings", settings.toString());
            cli.put("flydb.driver-cache", cache.toString());
            CliConfiguration configuration = new ConfigLoader().load(null, temporaryDirectory,
                    temporaryDirectory, Collections.<String, String>emptyMap(), cli);

            ResolvedDriver resolved = new DriverResolver().resolve(
                    temporaryDirectory.resolve("drivers"), configuration);

            Path cached = cache.resolve("example/fixture-jdbc/1.0/fixture-jdbc-1.0.jar");
            assertThat(resolved.source()).isEqualTo("Maven 私服 company-nexus");
            assertThat(resolved.urls()).containsExactly(cached.toUri().toURL());
            assertThat(cached).hasSameBinaryContentAs(servedJar);
        } finally {
            server.stop(0);
        }
    }

    private Map<String, String> baseConfiguration() {
        Map<String, String> cli = new HashMap<String, String>();
        cli.put("flydb.url", "jdbc:fixture:test");
        cli.put("flydb.driver", "fixture.IsolatedDriver");
        cli.put("flydb.driver-coordinate", "example:fixture-jdbc:1.0");
        return cli;
    }

    private CliConfiguration configuration(Map<String, String> cli) {
        return new ConfigLoader().load(null, temporaryDirectory, temporaryDirectory,
                Collections.<String, String>emptyMap(), cli);
    }

    private void createFakeDriverJar(Path jar) throws Exception {
        Path source = temporaryDirectory.resolve("src/fixture/IsolatedDriver.java");
        Files.createDirectories(source.getParent());
        Files.write(source, ("package fixture; public final class IsolatedDriver "
                + "implements java.sql.Driver {"
                + "public java.sql.Connection connect(String u, java.util.Properties p){return null;}"
                + "public boolean acceptsURL(String u){return true;}"
                + "public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p)"
                + "{return new java.sql.DriverPropertyInfo[0];}"
                + "public int getMajorVersion(){return 1;} public int getMinorVersion(){return 0;}"
                + "public boolean jdbcCompliant(){return false;}"
                + "public java.util.logging.Logger getParentLogger()"
                + "{return java.util.logging.Logger.getGlobal();}}").getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(temporaryDirectory.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(null, null, null, "-source", "8", "-target", "8",
                "-d", classes.toString(), source.toString())).isZero();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/IsolatedDriver.class"));
            output.write(Files.readAllBytes(classes.resolve("fixture/IsolatedDriver.class")));
            output.closeEntry();
        }
    }
}
