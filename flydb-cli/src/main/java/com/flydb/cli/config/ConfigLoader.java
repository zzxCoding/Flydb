package com.flydb.cli.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 读取默认值、Properties、环境变量与 CLI 参数并按优先级合并。 */
public final class ConfigLoader {

    private static final List<String> KNOWN_KEYS = Collections.unmodifiableList(Arrays.asList(
            "flydb.url", "flydb.user", "flydb.password", "flydb.password.file",
            "flydb.driver", "flydb.driver-coordinate", "flydb.driver-download",
            "flydb.driver-cache", "flydb.maven-settings", "flydb.maven-local-repository",
            "flydb.offline", "flydb.database-type", "flydb.locations", "flydb.encoding",
            "flydb.table", "flydb.baseline-version", "flydb.baseline-on-migrate",
            "flydb.validate-on-migrate", "flydb.out-of-order", "flydb.target-version",
            "flydb.start-version", "flydb.end-version", "flydb.version-selection",
            "flydb.version-source", "flydb.version-regex", "flydb.directory-glob",
            "flydb.file-glob", "flydb.path-glob", "flydb.directory-regex",
            "flydb.file-regex", "flydb.path-regex", "flydb.migration-order",
            "flydb.directory-version-regex", "flydb.placeholder-replacement",
            "flydb.placeholder-prefix",
            "flydb.placeholder-suffix", "flydb.sql-migration-prefix",
            "flydb.repeatable-migration-prefix", "flydb.undo-migration-prefix",
            "flydb.sql-migration-separator", "flydb.sql-migration-suffix", "flydb.callbacks",
            "flydb.clean-disabled", "flydb.lock-timeout-seconds", "flydb.batch-size"));

    public CliConfiguration load(Path explicitConfig, Path workingDirectory,
                                 Path installDirectory, Map<String, String> environment,
                                 Map<String, String> cliOverrides) {
        Map<String, String> values = defaults();
        Path configFile = locate(explicitConfig, workingDirectory, installDirectory);
        if (configFile != null) {
            Map<String, String> fileValues = readProperties(configFile);
            rejectUnknownKeys(fileValues);
            values.putAll(fileValues);
        }
        mergeEnvironment(values, environment);
        values.putAll(cliOverrides);
        resolveEnvironmentReferences(values, environment);
        resolvePasswordFile(values, workingDirectory);
        return new CliConfiguration(values);
    }

    private static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("flydb.locations", "filesystem:db/migration");
        values.put("flydb.driver-download", "auto");
        values.put("flydb.offline", "false");
        values.put("flydb.encoding", "UTF-8");
        values.put("flydb.table", "flydb_schema_history");
        values.put("flydb.baseline-version", "1");
        values.put("flydb.baseline-on-migrate", "false");
        values.put("flydb.validate-on-migrate", "true");
        values.put("flydb.out-of-order", "false");
        values.put("flydb.version-source", "file");
        values.put("flydb.migration-order", "version");
        values.put("flydb.directory-version-regex",
                "(?:^|/)(?<version>\\d+(?:\\.\\d+)*)(?=$|/)");
        values.put("flydb.placeholder-replacement", "true");
        values.put("flydb.placeholder-prefix", "${");
        values.put("flydb.placeholder-suffix", "}");
        values.put("flydb.sql-migration-prefix", "V");
        values.put("flydb.repeatable-migration-prefix", "R");
        values.put("flydb.undo-migration-prefix", "U");
        values.put("flydb.sql-migration-separator", "__");
        values.put("flydb.sql-migration-suffix", ".sql");
        values.put("flydb.clean-disabled", "true");
        values.put("flydb.lock-timeout-seconds", "60");
        values.put("flydb.batch-size", "1");
        return values;
    }

    private static Path locate(Path explicit, Path working, Path install) {
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "配置文件不存在: " + explicit);
            }
            return explicit;
        }
        Path local = working.resolve("flydb.conf");
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path installed = install.resolve("conf").resolve("flydb.conf");
        return Files.isRegularFile(installed) ? installed : null;
    }

    private static Map<String, String> readProperties(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "读取配置文件失败: " + file + ": " + e.getMessage(), e);
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String name : properties.stringPropertyNames()) {
            result.put(name, properties.getProperty(name));
        }
        return result;
    }

    private static void mergeEnvironment(Map<String, String> values,
                                         Map<String, String> environment) {
        for (String key : KNOWN_KEYS) {
            String environmentKey = key.toUpperCase(java.util.Locale.ROOT)
                    .replace('.', '_').replace('-', '_');
            if (environment.containsKey(environmentKey)) {
                values.put(key, environment.get(environmentKey));
            }
        }
        String prefix = "FLYDB_PLACEHOLDERS_";
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String name = entry.getKey().substring(prefix.length())
                        .toLowerCase(java.util.Locale.ROOT);
                values.put("flydb.placeholders." + name, entry.getValue());
            }
        }
    }

    private static void rejectUnknownKeys(Map<String, String> values) {
        for (String key : values.keySet()) {
            if (key.startsWith("flydb.") && !KNOWN_KEYS.contains(key)
                    && !key.startsWith("flydb.placeholders.")) {
                String suggestion = closestKey(key);
                throw new FlydbException(ErrorCode.UNKNOWN_CONFIG_KEY,
                        "未知键 " + key + (suggestion == null ? ""
                                : "；是否想写 " + suggestion + "？"));
            }
        }
    }

    private static String closestKey(String unknown) {
        String result = null;
        int distance = Integer.MAX_VALUE;
        for (String key : KNOWN_KEYS) {
            int candidate = levenshtein(unknown, key);
            if (candidate < distance) {
                distance = candidate;
                result = key;
            }
        }
        return distance <= 4 ? result : null;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replace = previous[j - 1]
                        + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), replace);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static void resolveEnvironmentReferences(Map<String, String> values,
                                                     Map<String, String> environment) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.startsWith("${env:") && value.endsWith("}")) {
                String name = value.substring("${env:".length(), value.length() - 1);
                String resolved = environment.get(name);
                if (resolved == null) {
                    throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                            "环境变量未设置: " + name + "（配置键 " + entry.getKey() + "）");
                }
                entry.setValue(resolved);
            }
        }
    }

    private static void resolvePasswordFile(Map<String, String> values, Path workingDirectory) {
        if (values.containsKey("flydb.password") || !values.containsKey("flydb.password.file")) {
            return;
        }
        Path file = java.nio.file.Paths.get(values.get("flydb.password.file"));
        if (!file.isAbsolute()) file = workingDirectory.resolve(file);
        try {
            String password = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            values.put("flydb.password", password.replaceFirst("[\\r\\n]+$", ""));
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "读取密码文件失败: " + file + ": " + e.getMessage(), e);
        }
    }
}
