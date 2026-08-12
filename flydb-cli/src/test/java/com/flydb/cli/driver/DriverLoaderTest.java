package com.flydb.cli.driver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.cli.config.CliConfiguration;
import com.flydb.cli.config.ConfigLoader;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DriverLoader")
class DriverLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("扫描 drivers jar 并由 DriverDataSource 直接调用驱动 connect")
    void loadsDriverJarWithoutDriverManagerRegistration() throws Exception {
        Path drivers = Files.createDirectories(temporaryDirectory.resolve("drivers"));
        createFakeDriverJar(drivers.resolve("fake-driver.jar"));
        Map<String, String> cli = new HashMap<String, String>();
        cli.put("flydb.url", "jdbc:fixture:test");
        cli.put("flydb.user", "alice");
        cli.put("flydb.password", "secret");
        cli.put("flydb.driver", "fixture.IsolatedDriver");
        CliConfiguration configuration = new ConfigLoader().load(null, temporaryDirectory,
                temporaryDirectory, Collections.<String, String>emptyMap(), cli);

        try (DriverContext context = new DriverLoader().open(drivers, configuration)) {
            assertThat(context.dataSource().getConnection().toString())
                    .isEqualTo("jdbc:fixture:test|alice|secret");
            assertThat(context.classLoader()).isNotSameAs(getClass().getClassLoader());
        }
    }

    private void createFakeDriverJar(Path jar) throws Exception {
        Path source = temporaryDirectory.resolve("src/fixture/IsolatedDriver.java");
        Files.createDirectories(source.getParent());
        Files.write(source, fakeDriverSource().getBytes(StandardCharsets.UTF_8));
        Path classes = Files.createDirectories(temporaryDirectory.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("测试需要 JDK 而不是 JRE").isNotNull();
        assertThat(compiler.run(null, null, null, "-source", "8", "-target", "8",
                "-d", classes.toString(), source.toString())).isZero();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (java.nio.file.DirectoryStream<Path> compiled = Files.newDirectoryStream(
                    classes.resolve("fixture"), "IsolatedDriver*.class")) {
                for (Path classFile : compiled) {
                    output.putNextEntry(new JarEntry("fixture/" + classFile.getFileName()));
                    output.write(Files.readAllBytes(classFile));
                    output.closeEntry();
                }
            }
        }
    }

    private static String fakeDriverSource() {
        return "package fixture;\n"
                + "import java.lang.reflect.*; import java.sql.*; import java.util.*;\n"
                + "public final class IsolatedDriver implements Driver {\n"
                + " public Connection connect(final String url, final Properties p) {\n"
                + "  if (!acceptsURL(url)) return null;\n"
                + "  return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),"
                + " new Class[]{Connection.class}, new InvocationHandler() {\n"
                + "   public Object invoke(Object x, Method m, Object[] a) {\n"
                + "    if (m.getName().equals(\"toString\")) return url + \"|\""
                + " + p.getProperty(\"user\") + \"|\" + p.getProperty(\"password\");\n"
                + "    if (m.getReturnType() == boolean.class) return false;\n"
                + "    if (m.getReturnType() == int.class) return 0; return null; } }); }\n"
                + " public boolean acceptsURL(String u) { return u.startsWith(\"jdbc:fixture:\"); }\n"
                + " public DriverPropertyInfo[] getPropertyInfo(String u, Properties p)"
                + " { return new DriverPropertyInfo[0]; }\n"
                + " public int getMajorVersion() { return 1; } public int getMinorVersion() { return 0; }\n"
                + " public boolean jdbcCompliant() { return false; }\n"
                + " public java.util.logging.Logger getParentLogger()"
                + " { return java.util.logging.Logger.getGlobal(); } }\n";
    }
}
