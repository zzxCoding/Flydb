package com.flydb.cli.config;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.callback.Callback;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** CLI 四层配置合并后的不可变、已类型化视图。 */
public final class CliConfiguration {

    private final Map<String, String> values;

    CliConfiguration(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(values));
    }

    public String url() {
        return values.get("flydb.url");
    }

    public String table() {
        return values.get("flydb.table");
    }

    public String password() {
        return values.get("flydb.password");
    }

    public String user() {
        return values.get("flydb.user");
    }

    public String driver() {
        return values.get("flydb.driver");
    }

    public String databaseType() { return values.get("flydb.database-type"); }
    public String baselineVersion() { return values.get("flydb.baseline-version"); }
    public String placeholderPrefix() { return values.get("flydb.placeholder-prefix"); }
    public String placeholderSuffix() { return values.get("flydb.placeholder-suffix"); }
    public String sqlMigrationPrefix() { return values.get("flydb.sql-migration-prefix"); }
    public String repeatableMigrationPrefix() { return values.get("flydb.repeatable-migration-prefix"); }
    public String undoMigrationPrefix() { return values.get("flydb.undo-migration-prefix"); }
    public String sqlMigrationSeparator() { return values.get("flydb.sql-migration-separator"); }
    public String sqlMigrationSuffix() { return values.get("flydb.sql-migration-suffix"); }
    public boolean baselineOnMigrate() { return booleanValue("flydb.baseline-on-migrate"); }
    public boolean validateOnMigrate() { return booleanValue("flydb.validate-on-migrate"); }
    public boolean outOfOrder() { return booleanValue("flydb.out-of-order"); }
    public boolean cleanDisabled() { return booleanValue("flydb.clean-disabled"); }
    public int lockTimeoutSeconds() { return intValue("flydb.lock-timeout-seconds"); }

    public Charset encoding() {
        String value = values.get("flydb.encoding");
        try {
            return Charset.forName(value);
        } catch (RuntimeException e) {
            throw invalidValue("flydb.encoding", value, "不是当前 JDK 支持的字符集");
        }
    }

    public List<String> locations() {
        String raw = values.get("flydb.locations");
        List<String> locations = new ArrayList<String>();
        for (String value : raw.split(",")) {
            if (!value.trim().isEmpty()) {
                locations.add(value.trim());
            }
        }
        return Collections.unmodifiableList(locations);
    }

    public Map<String, String> placeholders() {
        Map<String, String> placeholders = new LinkedHashMap<String, String>();
        String prefix = "flydb.placeholders.";
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                placeholders.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(placeholders);
    }

    /** 转换为 core 配置，连接和动态类加载器由 DriverLoader 提供。 */
    public FlydbConfiguration toCoreConfiguration(DataSource dataSource, ClassLoader classLoader) {
        FlydbConfiguration.Builder builder = FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations(locations().toArray(new String[locations().size()]))
                .encoding(encoding()).table(table()).baselineVersion(baselineVersion())
                .baselineOnMigrate(baselineOnMigrate())
                .validateOnMigrate(validateOnMigrate()).outOfOrder(outOfOrder())
                .placeholders(placeholders()).placeholderPrefix(placeholderPrefix())
                .placeholderSuffix(placeholderSuffix()).cleanDisabled(cleanDisabled())
                .lockTimeoutSeconds(lockTimeoutSeconds()).databaseType(databaseType())
                .classLoader(classLoader).callbacks(callbacks(classLoader))
                .sqlMigrationPrefix(sqlMigrationPrefix())
                .repeatableMigrationPrefix(repeatableMigrationPrefix())
                .undoMigrationPrefix(undoMigrationPrefix())
                .sqlMigrationSeparator(sqlMigrationSeparator())
                .sqlMigrationSuffix(sqlMigrationSuffix());
        return builder.build();
    }

    String value(String key) {
        return values.get(key);
    }

    private boolean booleanValue(String key) {
        String value = values.get(key);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw invalidValue(key, value, "应为 true 或 false");
    }

    private int intValue(String key) {
        try {
            return Integer.parseInt(values.get(key));
        } catch (RuntimeException e) {
            throw invalidValue(key, values.get(key), "应为整数");
        }
    }

    private Callback[] callbacks(ClassLoader classLoader) {
        String configured = values.get("flydb.callbacks");
        if (configured == null || configured.trim().isEmpty()) return new Callback[0];
        List<Callback> callbacks = new ArrayList<Callback>();
        for (String className : configured.split(",")) {
            try {
                Object callback = Class.forName(className.trim(), true, classLoader).newInstance();
                if (!(callback instanceof Callback)) {
                    throw invalidValue("flydb.callbacks", className,
                            "类未实现 com.flydb.core.callback.Callback");
                }
                callbacks.add((Callback) callback);
            } catch (FlydbException e) {
                throw e;
            } catch (Exception e) {
                throw invalidValue("flydb.callbacks", className,
                        "无法加载: " + e.getMessage());
            }
        }
        return callbacks.toArray(new Callback[callbacks.size()]);
    }

    private static FlydbException invalidValue(String key, String value, String reason) {
        return new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                "配置值无效: " + key + "=" + value + "（" + reason + "）");
    }
}
