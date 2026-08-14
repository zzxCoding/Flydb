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
import com.flydb.core.migration.MigrationOrder;
import com.flydb.core.migration.VersionSelection;
import com.flydb.core.migration.VersionSource;
import com.flydb.core.callback.Callback;
import com.flydb.core.Flydb;

/**
 * Flydb 不可变配置（设计 02 §2、00 §6.1）。
 *
 * <p>全部字段在构造完成后不再可变；集合字段经防御性拷贝 + {@code unmodifiable*} 包装。
 * 由 {@link Builder} 构造，{@link Builder#load()} 时 {@code validate()} 快速失败，错误消息指明
 * 具体非法项——杜绝旧原型「配置了但没生效」（{@code max_concurrent_tasks} 之教训）。
 *
 * <p>持有连接参数（url/user/password）或既有 {@link DataSource}。URL 模式的动态驱动加载由 CLI
 * 基础设施提供；core 命令运行时直接使用 {@link DataSource}。回调列表按注册顺序保持不可变。
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
    private final MigrationVersion targetVersion;
    private final MigrationVersion startVersion;
    private final MigrationVersion endVersion;
    private final VersionSelection versionSelection;
    private final String directoryGlob;
    private final String fileGlob;
    private final String pathGlob;
    private final String directoryRegex;
    private final String fileRegex;
    private final String pathRegex;
    private final MigrationOrder migrationOrder;
    private final String directoryVersionRegex;
    private final Map<String, String> placeholders;
    private final boolean placeholderReplacement;
    private final String placeholderPrefix;
    private final String placeholderSuffix;
    private final boolean cleanDisabled;
    private final int lockTimeoutSeconds;
    private final int batchSize;
    private final String databaseType;
    private final ClassLoader classLoader;
    private final List<Callback> callbacks;
    private final String sqlMigrationPrefix;
    private final String repeatableMigrationPrefix;
    private final String undoMigrationPrefix;
    private final String sqlMigrationSeparator;
    private final String sqlMigrationSuffix;

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
        this.targetVersion = b.targetVersion;
        this.startVersion = b.startVersion;
        this.endVersion = b.endVersion;
        this.versionSelection = b.buildVersionSelection();
        this.directoryGlob = b.directoryGlob;
        this.fileGlob = b.fileGlob;
        this.pathGlob = b.pathGlob;
        this.directoryRegex = b.directoryRegex;
        this.fileRegex = b.fileRegex;
        this.pathRegex = b.pathRegex;
        this.migrationOrder = b.migrationOrder;
        this.directoryVersionRegex = b.directoryVersionRegex;
        this.placeholders = Collections.unmodifiableMap(new HashMap<String, String>(b.placeholders));
        this.placeholderReplacement = b.placeholderReplacement;
        this.placeholderPrefix = b.placeholderPrefix;
        this.placeholderSuffix = b.placeholderSuffix;
        this.cleanDisabled = b.cleanDisabled;
        this.lockTimeoutSeconds = b.lockTimeoutSeconds;
        this.batchSize = b.batchSize;
        this.databaseType = b.databaseType;
        this.classLoader = b.classLoader;
        this.callbacks = Collections.unmodifiableList(new ArrayList<Callback>(b.callbacks));
        this.sqlMigrationPrefix = b.sqlMigrationPrefix;
        this.repeatableMigrationPrefix = b.repeatableMigrationPrefix;
        this.undoMigrationPrefix = b.undoMigrationPrefix;
        this.sqlMigrationSeparator = b.sqlMigrationSeparator;
        this.sqlMigrationSuffix = b.sqlMigrationSuffix;
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
    public MigrationVersion targetVersion() { return targetVersion; }
    public MigrationVersion startVersion() { return startVersion; }
    public MigrationVersion endVersion() { return endVersion; }
    public VersionSelection versionSelection() { return versionSelection; }
    public String directoryGlob() { return directoryGlob; }
    public String fileGlob() { return fileGlob; }
    public String pathGlob() { return pathGlob; }
    public String directoryRegex() { return directoryRegex; }
    public String fileRegex() { return fileRegex; }
    public String pathRegex() { return pathRegex; }
    public MigrationOrder migrationOrder() { return migrationOrder; }
    public String directoryVersionRegex() { return directoryVersionRegex; }
    public Map<String, String> placeholders() { return placeholders; }
    public boolean placeholderReplacement() { return placeholderReplacement; }
    public String placeholderPrefix() { return placeholderPrefix; }
    public String placeholderSuffix() { return placeholderSuffix; }
    public boolean cleanDisabled() { return cleanDisabled; }
    public int lockTimeoutSeconds() { return lockTimeoutSeconds; }
    public int batchSize() { return batchSize; }
    public String databaseType() { return databaseType; }
    public ClassLoader classLoader() { return classLoader; }
    public List<Callback> callbacks() { return callbacks; }
    public String sqlMigrationPrefix() { return sqlMigrationPrefix; }
    public String repeatableMigrationPrefix() { return repeatableMigrationPrefix; }
    public String undoMigrationPrefix() { return undoMigrationPrefix; }
    public String sqlMigrationSeparator() { return sqlMigrationSeparator; }
    public String sqlMigrationSuffix() { return sqlMigrationSuffix; }

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
        private MigrationVersion targetVersion;
        private MigrationVersion startVersion;
        private MigrationVersion endVersion;
        private VersionSelection.Mode requestedVersionSelection;
        private VersionSource versionSource = VersionSource.FILE;
        private String versionRegex;
        private String directoryGlob;
        private String fileGlob;
        private String pathGlob;
        private String directoryRegex;
        private String fileRegex;
        private String pathRegex;
        private MigrationOrder migrationOrder = MigrationOrder.VERSION;
        private String directoryVersionRegex =
                "(?:^|/)(?<version>\\d+(?:\\.\\d+)*)(?=$|/)";
        private Map<String, String> placeholders = new HashMap<String, String>();
        private boolean placeholderReplacement = true;
        private String placeholderPrefix = "${";
        private String placeholderSuffix = "}";
        private boolean cleanDisabled = true;
        private int lockTimeoutSeconds = 60;
        private int batchSize = 1;
        private String databaseType;
        private ClassLoader classLoader;
        private List<Callback> callbacks = new ArrayList<Callback>();
        private String sqlMigrationPrefix = "V";
        private String repeatableMigrationPrefix = "R";
        private String undoMigrationPrefix = "U";
        private String sqlMigrationSeparator = "__";
        private String sqlMigrationSuffix = ".sql";

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
        public Builder targetVersion(String version) {
            this.targetVersion = version == null || version.isEmpty()
                    ? null : MigrationVersion.parse(version);
            return this;
        }
        public Builder startVersion(String version) {
            this.startVersion = version == null || version.isEmpty()
                    ? null : MigrationVersion.parse(version);
            return this;
        }
        public Builder endVersion(String version) {
            this.endVersion = version == null || version.isEmpty()
                    ? null : MigrationVersion.parse(version);
            return this;
        }
        public Builder versionSelection(String mode) {
            if (mode == null || mode.trim().isEmpty()) {
                this.requestedVersionSelection = null;
                return this;
            }
            String normalized = mode.trim().toUpperCase(java.util.Locale.ROOT)
                    .replace('-', '_');
            try {
                this.requestedVersionSelection = VersionSelection.Mode.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.version-selection 不支持: " + mode
                                + "（可选 exact|range|family|family-range|regex）");
            }
            if (this.requestedVersionSelection == VersionSelection.Mode.ALL) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.version-selection 不支持显式配置 all；不配置版本参数即可选择全部迁移");
            }
            return this;
        }
        public Builder versionSource(String source) {
            if (source == null || source.trim().isEmpty()) {
                this.versionSource = VersionSource.FILE;
                return this;
            }
            try {
                this.versionSource = VersionSource.valueOf(
                        source.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.version-source 不支持: " + source + "（可选 file|directory）");
            }
            return this;
        }
        public Builder versionRegex(String regex) { this.versionRegex = emptyToNull(regex); return this; }
        public Builder directoryGlob(String value) { this.directoryGlob = emptyToNull(value); return this; }
        public Builder fileGlob(String value) { this.fileGlob = emptyToNull(value); return this; }
        public Builder pathGlob(String value) { this.pathGlob = emptyToNull(value); return this; }
        public Builder directoryRegex(String value) { this.directoryRegex = emptyToNull(value); return this; }
        public Builder fileRegex(String value) { this.fileRegex = emptyToNull(value); return this; }
        public Builder pathRegex(String value) { this.pathRegex = emptyToNull(value); return this; }
        public Builder migrationOrder(String value) {
            if (value == null || value.trim().isEmpty()) {
                this.migrationOrder = MigrationOrder.VERSION;
                return this;
            }
            try {
                this.migrationOrder = MigrationOrder.valueOf(value.trim()
                        .toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.migration-order 不支持: " + value
                                + "（可选 version|directory-version）");
            }
            return this;
        }
        public Builder directoryVersionRegex(String value) {
            this.directoryVersionRegex = emptyToNull(value);
            return this;
        }

        public Builder placeholders(Map<String, String> placeholders) {
            this.placeholders = new HashMap<String, String>(placeholders);
            return this;
        }

        public Builder placeholderReplacement(boolean enabled) {
            this.placeholderReplacement = enabled;
            return this;
        }

        public Builder placeholderPrefix(String prefix) { this.placeholderPrefix = prefix; return this; }
        public Builder placeholderSuffix(String suffix) { this.placeholderSuffix = suffix; return this; }
        public Builder cleanDisabled(boolean flag) { this.cleanDisabled = flag; return this; }
        public Builder lockTimeoutSeconds(int seconds) { this.lockTimeoutSeconds = seconds; return this; }
        public Builder batchSize(int size) { this.batchSize = size; return this; }
        public Builder databaseType(String typeName) { this.databaseType = typeName; return this; }
        public Builder classLoader(ClassLoader classLoader) { this.classLoader = classLoader; return this; }
        public Builder callbacks(Callback... callbacks) {
            this.callbacks = new ArrayList<Callback>(Arrays.asList(callbacks));
            return this;
        }
        public Builder sqlMigrationPrefix(String value) { this.sqlMigrationPrefix = value; return this; }
        public Builder repeatableMigrationPrefix(String value) { this.repeatableMigrationPrefix = value; return this; }
        public Builder undoMigrationPrefix(String value) { this.undoMigrationPrefix = value; return this; }
        public Builder sqlMigrationSeparator(String value) { this.sqlMigrationSeparator = value; return this; }
        public Builder sqlMigrationSuffix(String value) { this.sqlMigrationSuffix = value; return this; }

        /**
         * 校验并构造不可变配置。
         *
         * @throws FlydbException(FLYDB-4002) url/dataSource 未二选一，或其它必填项缺失
         * @throws FlydbException(FLYDB-2001) baselineVersion 字符串非法
         */
        public Flydb load() {
            return new Flydb(build());
        }

        /** 构造配置对象，供命令适配器与测试注入；常规用户使用 {@link #load()}。 */
        public FlydbConfiguration build() {
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
            requireNonEmpty(sqlMigrationPrefix, "flydb.sql-migration-prefix");
            requireNonEmpty(repeatableMigrationPrefix, "flydb.repeatable-migration-prefix");
            requireNonEmpty(undoMigrationPrefix, "flydb.undo-migration-prefix");
            requireNonEmpty(sqlMigrationSeparator, "flydb.sql-migration-separator");
            requireNonEmpty(sqlMigrationSuffix, "flydb.sql-migration-suffix");
            if (lockTimeoutSeconds < 0) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.lock-timeout-seconds 不可为负数: " + lockTimeoutSeconds);
            }
            if (batchSize < 1) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.batch-size 不可小于 1: " + batchSize);
            }
            if (startVersion != null && endVersion != null
                    && startVersion.compareTo(endVersion) > 0) {
                throw new FlydbException(ErrorCode.INVALID_VERSION,
                        "flydb.start-version " + startVersion
                                + " 不可大于 flydb.end-version " + endVersion);
            }
            validateVersionSelection();
            validatePathRules();
        }

        private void validatePathRules() {
            validateExclusive("directory", directoryGlob, directoryRegex);
            validateExclusive("file", fileGlob, fileRegex);
            validateExclusive("path", pathGlob, pathRegex);
            validateRegex("flydb.directory-regex", directoryRegex);
            validateRegex("flydb.file-regex", fileRegex);
            validateRegex("flydb.path-regex", pathRegex);
            if (versionSource == VersionSource.DIRECTORY
                    || migrationOrder == MigrationOrder.DIRECTORY_VERSION) {
                if (directoryVersionRegex == null) {
                    throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                            "使用目录版本时 flydb.directory-version-regex 不能为空");
                }
                validateRegex("flydb.directory-version-regex", directoryVersionRegex);
            }
        }

        private static void validateExclusive(String dimension, String glob, String regex) {
            if (glob != null && regex != null) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb." + dimension + "-glob 与 flydb." + dimension
                                + "-regex 不可同时配置");
            }
        }

        private static void validateRegex(String key, String value) {
            if (value == null) return;
            try {
                java.util.regex.Pattern.compile(value);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        key + " 不是合法正则: " + e.getDescription(), e);
            }
        }

        private void validateVersionSelection() {
            VersionSelection.Mode mode = effectiveVersionSelectionMode();
            boolean hasTarget = targetVersion != null;
            boolean hasRange = startVersion != null || endVersion != null;
            boolean hasRegex = versionRegex != null;
            switch (mode) {
                case ALL:
                    if (hasRegex) {
                        throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                                "配置 flydb.version-regex 时必须设置 flydb.version-selection=regex");
                    }
                    return;
                case EXACT:
                case FAMILY:
                    if (!hasTarget || hasRange || hasRegex) {
                        throw incompatibleVersionSelection(mode,
                                "必须且只能配置 flydb.target-version");
                    }
                    return;
                case RANGE:
                case FAMILY_RANGE:
                    if (hasTarget || !hasRange || hasRegex) {
                        throw incompatibleVersionSelection(mode,
                                "必须配置 start-version/end-version 中至少一个，且不可配置 target-version");
                    }
                    return;
                case REGEX:
                    if (!hasRegex || hasTarget || hasRange) {
                        throw incompatibleVersionSelection(mode,
                                "必须且只能配置 flydb.version-regex");
                    }
                    return;
                default:
                    throw new IllegalStateException("未知版本筛选模式: " + mode);
            }
        }

        private VersionSelection buildVersionSelection() {
            VersionSelection.Mode mode = effectiveVersionSelectionMode();
            switch (mode) {
                case ALL: return VersionSelection.all(versionSource);
                case EXACT: return VersionSelection.exact(targetVersion, versionSource);
                case RANGE: return VersionSelection.range(startVersion, endVersion, versionSource);
                case FAMILY: return VersionSelection.family(targetVersion, versionSource);
                case FAMILY_RANGE:
                    return VersionSelection.familyRange(startVersion, endVersion, versionSource);
                case REGEX: return VersionSelection.regex(versionRegex, versionSource);
                default: throw new IllegalStateException("未知版本筛选模式: " + mode);
            }
        }

        private VersionSelection.Mode effectiveVersionSelectionMode() {
            if (requestedVersionSelection != null) {
                return requestedVersionSelection;
            }
            if (targetVersion != null) {
                return VersionSelection.Mode.EXACT;
            }
            if (startVersion != null || endVersion != null) {
                return VersionSelection.Mode.RANGE;
            }
            return VersionSelection.Mode.ALL;
        }

        private static FlydbException incompatibleVersionSelection(VersionSelection.Mode mode,
                                                                   String requirement) {
            return new FlydbException(ErrorCode.INVALID_VERSION,
                    "flydb.version-selection=" + mode.name().toLowerCase(java.util.Locale.ROOT)
                            .replace('_', '-') + " 时" + requirement);
        }

        private static String emptyToNull(String value) {
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }

        private static void requireNonEmpty(String value, String key) {
            if (value == null || value.isEmpty()) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, key + " 不能为空");
            }
        }
    }
}
