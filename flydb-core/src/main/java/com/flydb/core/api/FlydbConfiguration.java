package com.flydb.core.api;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationVersion;

/**
 * Flydb 不可变配置（设计 02 §2、00 §6.1）。
 *
 * <p>全部字段在构造完成后不再可变；集合字段经防御性拷贝 + {@code unmodifiable*} 包装。
 * 由 {@link Builder} 构造，{@link Builder#load()} 时 {@code validate()} 快速失败，错误消息指明
 * 具体非法项——杜绝旧原型「配置了但没生效」（{@code max_concurrent_tasks} 之教训）。
 *
 * <p>阶段 1 范围：持有连接参数（url/user/password）或既有 {@link DataSource}，但<b>不</b>在此构造
 * {@code DriverDataSource}——动态驱动加载（设计 06 §6）属阶段 3 运行期基础设施。
 * {@code callbacks}（设计 02 §2、05 §8）随阶段 4 的 Callback SPI 一并补齐。
 */
public final class FlydbConfiguration {

    private final DataSource dataSource;
    private final String url;
    private final String user;
    private final String password;
    private final List<String> locations;
    private final Charset encoding;
    private final String table;
    private final MigrationVersion baselineVersion;
    private final boolean baselineOnMigrate;
    private final boolean validateOnMigrate;
    private final boolean outOfOrder;
    private final Map<String, String> placeholders;
    private final String placeholderPrefix;
    private final String placeholderSuffix;
    private final boolean cleanDisabled;
    private final int lockTimeoutSeconds;
    private final String databaseType;
    private final ClassLoader classLoader;

    private FlydbConfiguration(Builder b) {
        this.dataSource = b.dataSource;
        this.url = b.url;
        this.user = b.user;
        this.password = b.password;
        this.locations = Collections.unmodifiableList(new ArrayList<String>(b.locations));
        this.encoding = b.encoding;
        this.table = b.table;
        this.baselineVersion = b.baselineVersion;
        this.baselineOnMigrate = b.baselineOnMigrate;
        this.validateOnMigrate = b.validateOnMigrate;
        this.outOfOrder = b.outOfOrder;
        this.placeholders = Collections.unmodifiableMap(new HashMap<String, String>(b.placeholders));
        this.placeholderPrefix = b.placeholderPrefix;
        this.placeholderSuffix = b.placeholderSuffix;
        this.cleanDisabled = b.cleanDisabled;
        this.lockTimeoutSeconds = b.lockTimeoutSeconds;
        this.databaseType = b.databaseType;
        this.classLoader = b.classLoader;
    }

    public static Builder builder() {
        return new Builder();
    }

    public DataSource dataSource() { return dataSource; }
    public String url() { return url; }
    public String user() { return user; }
    public String password() { return password; }
    public List<String> locations() { return locations; }
    public Charset encoding() { return encoding; }
    public String table() { return table; }
    public MigrationVersion baselineVersion() { return baselineVersion; }
    public boolean baselineOnMigrate() { return baselineOnMigrate; }
    public boolean validateOnMigrate() { return validateOnMigrate; }
    public boolean outOfOrder() { return outOfOrder; }
    public Map<String, String> placeholders() { return placeholders; }
    public String placeholderPrefix() { return placeholderPrefix; }
    public String placeholderSuffix() { return placeholderSuffix; }
    public boolean cleanDisabled() { return cleanDisabled; }
    public int lockTimeoutSeconds() { return lockTimeoutSeconds; }
    public String databaseType() { return databaseType; }
    public ClassLoader classLoader() { return classLoader; }

    /**
     * 可变构建器（设计 02 §2）。每个 setter 返回 {@code this}；{@link #load()} 校验并产出不可变配置。
     */
    public static final class Builder {

        private DataSource dataSource;
        private String url;
        private String user;
        private String password;
        private List<String> locations = new ArrayList<String>(Collections.singletonList("classpath:db/migration"));
        private Charset encoding = StandardCharsets.UTF_8;
        private String table = "flydb_schema_history";
        private MigrationVersion baselineVersion = MigrationVersion.parse("1");
        private boolean baselineOnMigrate = false;
        private boolean validateOnMigrate = true;
        private boolean outOfOrder = false;
        private Map<String, String> placeholders = new HashMap<String, String>();
        private String placeholderPrefix = "${";
        private String placeholderSuffix = "}";
        private boolean cleanDisabled = true;
        private int lockTimeoutSeconds = 60;
        private String databaseType;
        private ClassLoader classLoader;

        public Builder dataSource(DataSource dataSource) { this.dataSource = dataSource; return this; }
        public Builder url(String url) { this.url = url; return this; }
        public Builder user(String user) { this.user = user; return this; }
        public Builder password(String password) { this.password = password; return this; }

        public Builder locations(String... locations) {
            this.locations = new ArrayList<String>(Arrays.asList(locations));
            return this;
        }

        public Builder encoding(Charset encoding) {
            if (encoding == null) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, "encoding 不能为空");
            }
            this.encoding = encoding;
            return this;
        }

        public Builder table(String table) { this.table = table; return this; }

        public Builder baselineVersion(String version) {
            this.baselineVersion = MigrationVersion.parse(version);
            return this;
        }

        public Builder baselineVersion(MigrationVersion version) {
            this.baselineVersion = version;
            return this;
        }

        public Builder baselineOnMigrate(boolean flag) { this.baselineOnMigrate = flag; return this; }
        public Builder validateOnMigrate(boolean flag) { this.validateOnMigrate = flag; return this; }
        public Builder outOfOrder(boolean flag) { this.outOfOrder = flag; return this; }

        public Builder placeholders(Map<String, String> placeholders) {
            this.placeholders = new HashMap<String, String>(placeholders);
            return this;
        }

        public Builder placeholderPrefix(String prefix) { this.placeholderPrefix = prefix; return this; }
        public Builder placeholderSuffix(String suffix) { this.placeholderSuffix = suffix; return this; }
        public Builder cleanDisabled(boolean flag) { this.cleanDisabled = flag; return this; }
        public Builder lockTimeoutSeconds(int seconds) { this.lockTimeoutSeconds = seconds; return this; }
        public Builder databaseType(String typeName) { this.databaseType = typeName; return this; }
        public Builder classLoader(ClassLoader classLoader) { this.classLoader = classLoader; return this; }

        /**
         * 校验并构造不可变配置。
         *
         * @throws FlydbException(FLYDB-4002) url/dataSource 未二选一，或其它必填项缺失
         * @throws FlydbException(FLYDB-2001) baselineVersion 字符串非法
         */
        public FlydbConfiguration load() {
            if (classLoader == null) {
                classLoader = Thread.currentThread().getContextClassLoader();
                if (classLoader == null) {
                    classLoader = FlydbConfiguration.class.getClassLoader();
                }
            }
            validate();
            return new FlydbConfiguration(this);
        }

        private void validate() {
            boolean hasUrl = url != null && !url.isEmpty();
            boolean hasDataSource = dataSource != null;
            if (hasUrl == hasDataSource) {
                // 同时为 true（两者都给）或同时为 false（都没给）
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        hasDataSource
                                ? "url 与 dataSource 不可同时提供，请二选一"
                                : "必须提供 flydb.url（或 dataSource）");
            }
            requireNonEmpty(table, "flydb.table");
            requireNonEmpty(placeholderPrefix, "flydb.placeholder-prefix");
            requireNonEmpty(placeholderSuffix, "flydb.placeholder-suffix");
            if (lockTimeoutSeconds < 0) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.lock-timeout-seconds 不可为负数: " + lockTimeoutSeconds);
            }
        }

        private static void requireNonEmpty(String value, String key) {
            if (value == null || value.isEmpty()) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, key + " 不能为空");
            }
        }
    }
}
