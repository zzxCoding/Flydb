package com.flydb.runtime.config;

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

    private static final String IMPORT = "flydb.config-import";
    private static final List<String> SCRIPT_KEYS = Arrays.asList("flydb.locations", "flydb.encoding",
            "flydb.version-source", "flydb.directory-version-regex", "flydb.migration-order",
            "flydb.directory-glob", "flydb.path-glob", "flydb.file-glob",
            "flydb.directory-regex", "flydb.path-regex", "flydb.file-regex",
            "flydb.sql-migration-prefix", "flydb.undo-migration-prefix", "flydb.repeatable-migration-prefix",
            "flydb.sql-migration-separator", "flydb.sql-migration-suffix");

    private static final List<String> KNOWN_KEYS = Collections.unmodifiableList(Arrays.asList(
            IMPORT, "flydb.url", "flydb.user", "flydb.password", "flydb.password.file",
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
        return load(explicitConfig, workingDirectory, installDirectory, environment, cliOverrides, false);
    }

    /** 离线创建脚本只解析发现相关字段，不访问凭据或加载驱动。 */
    public CliConfiguration loadForScripts(Path explicitConfig, Path workingDirectory, Path installDirectory,
                                           Map<String, String> environment, Map<String, String> cliOverrides) {
        return load(explicitConfig, workingDirectory, installDirectory, environment, cliOverrides, true);
    }

    private CliConfiguration load(Path explicitConfig, Path workingDirectory, Path installDirectory,
                                  Map<String, String> environment, Map<String, String> cliOverrides, boolean scriptsOnly) {
        Map<String, String> values = defaults();
        Map<String, String> sources = new LinkedHashMap<String, String>();
        Map<String, String> declaredAt = new LinkedHashMap<String, String>();
        markSources(sources, values, "DEFAULT");
        Path configFile = locate(explicitConfig, workingDirectory, installDirectory);
        if (configFile != null) {
            loadFile(configFile, values, sources, declaredAt, new java.util.HashSet<Path>());
        }
        Map<String, String> environmentValues = new LinkedHashMap<String, String>();
        mergeEnvironment(environmentValues, environment, declaredAt);
        values.putAll(environmentValues);
        markSources(sources, environmentValues, "ENVIRONMENT");
        values.putAll(cliOverrides);
        markSources(sources, cliOverrides, "OVERRIDE");
        declaredAt.keySet().removeAll(cliOverrides.keySet());
        if (scriptsOnly) {
            Map<String, String> discovery = defaults();
            for (String key : SCRIPT_KEYS) if (values.containsKey(key)) discovery.put(key, values.get(key));
            values = discovery;
            declaredAt.keySet().retainAll(discovery.keySet());
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (value != null && value.startsWith("${env:") && value.endsWith("}"))
                sources.put(entry.getKey(), "ENV_REFERENCE:" + value.substring(6, value.length() - 1));
        }
        resolveEnvironmentReferences(values, environment);
        if ((values.get("flydb.password") == null || values.get("flydb.password").isEmpty())
                && values.containsKey("flydb.password.file")) {
            sources.put("flydb.password", "PASSWORD_FILE");
            declaredAt.remove("flydb.password");
        }
        resolvePasswordFile(values, workingDirectory);
        return new CliConfiguration(values, sources, declaredAt);
    }

    private static void loadFile(Path file, Map<String, String> values, Map<String, String> sources,
                                 Map<String, String> declaredAt, java.util.Set<Path> chain) {
        try {
            Path actual = file.toRealPath();
            if (!Files.isRegularFile(actual)) throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, "配置必须是普通文件: " + file);
            boolean imported = !chain.isEmpty();
            if (!chain.add(actual)) throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, "循环导入配置: " + file);
            Map<String, String> fields = readProperties(actual);
            rejectUnknownKeys(fields);
            String parent = fields.remove(IMPORT);
            if (parent != null) {
                if (parent.trim().isEmpty()) throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, IMPORT + " 不能为空");
                loadFile(actual.getParent().resolve(parent.trim()), values, sources, declaredAt, chain);
            }
            if (parent != null && fields.containsKey("flydb.password.file") && !fields.containsKey("flydb.password")) {
                values.remove("flydb.password");
                sources.remove("flydb.password");
                declaredAt.remove("flydb.password");
            }
            if (parent != null && fields.containsKey("flydb.password") && !fields.containsKey("flydb.password.file")) {
                values.remove("flydb.password.file");
                sources.remove("flydb.password.file");
                declaredAt.remove("flydb.password.file");
            }
            values.putAll(fields);
            markSources(sources, fields, imported ? "IMPORTED_FILE" : "FILE");
            for (String key : fields.keySet()) declaredAt.put(key, actual.toString());
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, "读取配置文件失败: " + file, e);
        }
    }

    /** Validate literal file values; environment references are checked when the runtime resolves them. */
    public static void validateDocumentValues(Map<String, String> fields) {
        validateDocumentValues(fields, null);
    }

    /** 已知目标文件时按最终位置校验导入链，编辑器中的纯文本草稿延迟至保存再解析。 */
    public static void validateDocumentValues(Map<String, String> fields, Path destination) {
        rejectUnknownKeys(fields);
        Map<String, String> literals = new LinkedHashMap<String, String>();
        if (fields.containsKey(IMPORT)) {
            String parent = fields.get(IMPORT).trim();
            if (parent.isEmpty()) throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, IMPORT + " 不能为空");
            if (destination == null) return;
            java.util.Set<Path> chain = new java.util.HashSet<Path>();
            Path actual = destination.toAbsolutePath().normalize();
            chain.add(actual);
            loadFile(actual.getParent().resolve(parent), literals, new LinkedHashMap<String, String>(), new LinkedHashMap<String, String>(), chain);
        }
        literals.putAll(fields);
        Map<String, String> values = defaults();
        for (Map.Entry<String, String> entry : literals.entrySet()) {
            String value = entry.getValue();
            if (value != null && !(value.startsWith("${env:") && value.endsWith("}"))) values.put(entry.getKey(), value);
        }
        new CliConfiguration(values).validateFileValues();
    }

    public static List<String> knownKeys() { return KNOWN_KEYS; }

    /** 供配置表单展示加载器的内置值，不包含本机环境变量或用户文件。 */
    public static Map<String, String> defaultValues() { return Collections.unmodifiableMap(defaults()); }

    private static void markSources(Map<String, String> sources, Map<String, String> values, String source) {
        for (String key : values.keySet()) sources.put(key, source);
    }

    static void resolvePaths(Map<String, String> values, Path working) {
        String[] keys = {"flydb.driver-cache", "flydb.maven-settings", "flydb.maven-local-repository"};
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isEmpty()) values.put(key, working.resolve(value).toAbsolutePath().normalize().toString());
        }
        String locations = values.get("flydb.locations");
        if (locations == null) return;
        StringBuilder result = new StringBuilder();
        for (String location : locations.split(",")) {
            if (result.length() > 0) result.append(',');
            String value = location.trim();
            if (value.startsWith("filesystem:"))
                value = "filesystem:" + working.resolve(value.substring(11)).toAbsolutePath().normalize();
            result.append(value);
        }
        values.put("flydb.locations", result.toString());
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

    public static Path locate(Path explicit, Path working, Path install) {
        if (explicit != null) {
            explicit = working.resolve(explicit).toAbsolutePath().normalize();
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
                                         Map<String, String> environment, Map<String, String> declaredAt) {
        for (String key : KNOWN_KEYS) {
            if (IMPORT.equals(key)) continue;
            String environmentKey = key.toUpperCase(java.util.Locale.ROOT)
                    .replace('.', '_').replace('-', '_');
            if (environment.containsKey(environmentKey)) {
                values.put(key, environment.get(environmentKey));
                declaredAt.put(key, environmentKey);
            }
        }
        String prefix = "FLYDB_PLACEHOLDERS_";
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String name = entry.getKey().substring(prefix.length())
                        .toLowerCase(java.util.Locale.ROOT);
                values.put("flydb.placeholders." + name, entry.getValue());
                declaredAt.put("flydb.placeholders." + name, entry.getKey());
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
