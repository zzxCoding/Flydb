package com.flydb.boot3.autoconfigure;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.callback.Callback;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Spring Boot 3 的 {@code flydb.*} 配置绑定。 */
@ConfigurationProperties(prefix = "flydb")
public class FlydbProperties {

    private boolean enabled = true;
    private List<String> locations = new ArrayList<>();
    private Charset encoding = Charset.forName("UTF-8");
    private String table = "flydb_schema_history";
    private String baselineVersion = "1";
    private boolean baselineOnMigrate;
    private boolean validateOnMigrate = true;
    private boolean outOfOrder;
    private Map<String, String> placeholders = new LinkedHashMap<>();
    private String placeholderPrefix = "${";
    private String placeholderSuffix = "}";
    private String sqlMigrationPrefix = "V";
    private String repeatableMigrationPrefix = "R";
    private String undoMigrationPrefix = "U";
    private String sqlMigrationSeparator = "__";
    private String sqlMigrationSuffix = ".sql";
    private List<String> callbacks = new ArrayList<>();
    private boolean cleanDisabled = true;
    private int lockTimeoutSeconds = 60;
    private String databaseType;
    private String url;
    private String user;
    private String password;
    private String driver;

    public FlydbProperties() {
        locations.add("classpath:db/migration");
    }

    FlydbConfiguration toCoreConfiguration(DataSource dataSource, ClassLoader classLoader) {
        return FlydbConfiguration.builder()
                .dataSource(dataSource)
                .locations(locations.toArray(new String[0]))
                .encoding(encoding)
                .table(table)
                .baselineVersion(baselineVersion)
                .baselineOnMigrate(baselineOnMigrate)
                .validateOnMigrate(validateOnMigrate)
                .outOfOrder(outOfOrder)
                .placeholders(placeholders)
                .placeholderPrefix(placeholderPrefix)
                .placeholderSuffix(placeholderSuffix)
                .sqlMigrationPrefix(sqlMigrationPrefix)
                .repeatableMigrationPrefix(repeatableMigrationPrefix)
                .undoMigrationPrefix(undoMigrationPrefix)
                .sqlMigrationSeparator(sqlMigrationSeparator)
                .sqlMigrationSuffix(sqlMigrationSuffix)
                .callbacks(loadCallbacks(classLoader))
                .cleanDisabled(cleanDisabled)
                .lockTimeoutSeconds(lockTimeoutSeconds)
                .databaseType(databaseType)
                .classLoader(classLoader)
                .build();
    }

    private Callback[] loadCallbacks(ClassLoader classLoader) {
        List<Callback> instances = new ArrayList<>();
        for (String className : callbacks) {
            try {
                Object candidate = Class.forName(className.trim(), true, classLoader)
                        .getDeclaredConstructor().newInstance();
                if (!(candidate instanceof Callback)) {
                    throw invalidCallback(className, "类未实现 com.flydb.core.callback.Callback", null);
                }
                instances.add((Callback) candidate);
            } catch (FlydbException e) {
                throw e;
            } catch (Exception e) {
                throw invalidCallback(className, "无法加载: " + e.getMessage(), e);
            }
        }
        return instances.toArray(new Callback[0]);
    }

    private static FlydbException invalidCallback(String className, String reason, Throwable cause) {
        return new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                "配置值无效: flydb.callbacks=" + className + "（" + reason + "）", cause);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }
    public Charset getEncoding() { return encoding; }
    public void setEncoding(Charset encoding) { this.encoding = encoding; }
    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }
    public String getBaselineVersion() { return baselineVersion; }
    public void setBaselineVersion(String baselineVersion) { this.baselineVersion = baselineVersion; }
    public boolean isBaselineOnMigrate() { return baselineOnMigrate; }
    public void setBaselineOnMigrate(boolean baselineOnMigrate) { this.baselineOnMigrate = baselineOnMigrate; }
    public boolean isValidateOnMigrate() { return validateOnMigrate; }
    public void setValidateOnMigrate(boolean validateOnMigrate) { this.validateOnMigrate = validateOnMigrate; }
    public boolean isOutOfOrder() { return outOfOrder; }
    public void setOutOfOrder(boolean outOfOrder) { this.outOfOrder = outOfOrder; }
    public Map<String, String> getPlaceholders() { return placeholders; }
    public void setPlaceholders(Map<String, String> placeholders) { this.placeholders = placeholders; }
    public String getPlaceholderPrefix() { return placeholderPrefix; }
    public void setPlaceholderPrefix(String placeholderPrefix) { this.placeholderPrefix = placeholderPrefix; }
    public String getPlaceholderSuffix() { return placeholderSuffix; }
    public void setPlaceholderSuffix(String placeholderSuffix) { this.placeholderSuffix = placeholderSuffix; }
    public String getSqlMigrationPrefix() { return sqlMigrationPrefix; }
    public void setSqlMigrationPrefix(String sqlMigrationPrefix) { this.sqlMigrationPrefix = sqlMigrationPrefix; }
    public String getRepeatableMigrationPrefix() { return repeatableMigrationPrefix; }
    public void setRepeatableMigrationPrefix(String repeatableMigrationPrefix) { this.repeatableMigrationPrefix = repeatableMigrationPrefix; }
    public String getUndoMigrationPrefix() { return undoMigrationPrefix; }
    public void setUndoMigrationPrefix(String undoMigrationPrefix) { this.undoMigrationPrefix = undoMigrationPrefix; }
    public String getSqlMigrationSeparator() { return sqlMigrationSeparator; }
    public void setSqlMigrationSeparator(String sqlMigrationSeparator) { this.sqlMigrationSeparator = sqlMigrationSeparator; }
    public String getSqlMigrationSuffix() { return sqlMigrationSuffix; }
    public void setSqlMigrationSuffix(String sqlMigrationSuffix) { this.sqlMigrationSuffix = sqlMigrationSuffix; }
    public List<String> getCallbacks() { return callbacks; }
    public void setCallbacks(List<String> callbacks) { this.callbacks = callbacks; }
    public boolean isCleanDisabled() { return cleanDisabled; }
    public void setCleanDisabled(boolean cleanDisabled) { this.cleanDisabled = cleanDisabled; }
    public int getLockTimeoutSeconds() { return lockTimeoutSeconds; }
    public void setLockTimeoutSeconds(int lockTimeoutSeconds) { this.lockTimeoutSeconds = lockTimeoutSeconds; }
    public String getDatabaseType() { return databaseType; }
    public void setDatabaseType(String databaseType) { this.databaseType = databaseType; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDriver() { return driver; }
    public void setDriver(String driver) { this.driver = driver; }
}
