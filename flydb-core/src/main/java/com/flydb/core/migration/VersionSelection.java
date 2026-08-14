package com.flydb.core.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.log.Log;

/**
 * 不可变版本筛选规则。
 *
 * <p>筛选模式与版本来源正交：同一个精确、版本族或范围规则既可作用于文件版本，
 * 也可作用于从相对目录提取的目录版本。未显式选择时使用 {@link #all()}，保留
 * Flydb 的完整迁移集合与 repeatable 行为。
 */
public final class VersionSelection {

    public enum Mode {
        ALL,
        EXACT,
        RANGE,
        FAMILY,
        FAMILY_RANGE,
        REGEX
    }

    private final Mode mode;
    private final VersionSource source;
    private final MigrationVersion target;
    private final MigrationVersion start;
    private final MigrationVersion end;
    private final String regexText;
    private final Pattern regex;

    private VersionSelection(Mode mode, VersionSource source, MigrationVersion target,
                             MigrationVersion start, MigrationVersion end,
                             String regexText, Pattern regex) {
        this.mode = mode;
        this.source = source;
        this.target = target;
        this.start = start;
        this.end = end;
        this.regexText = regexText;
        this.regex = regex;
    }

    public static VersionSelection all() {
        return all(VersionSource.FILE);
    }

    public static VersionSelection all(VersionSource source) {
        return new VersionSelection(Mode.ALL, requiredSource(source),
                null, null, null, null, null);
    }

    public static VersionSelection exact(MigrationVersion target, VersionSource source) {
        return new VersionSelection(Mode.EXACT, requiredSource(source),
                requiredVersion(target, "target-version"), null, null, null, null);
    }

    public static VersionSelection range(MigrationVersion start, MigrationVersion end,
                                         VersionSource source) {
        requireAnyBoundary(start, end);
        return new VersionSelection(Mode.RANGE, requiredSource(source),
                null, start, end, null, null);
    }

    public static VersionSelection family(MigrationVersion target, VersionSource source) {
        return new VersionSelection(Mode.FAMILY, requiredSource(source),
                requiredVersion(target, "target-version"), null, null, null, null);
    }

    public static VersionSelection familyRange(MigrationVersion start, MigrationVersion end,
                                               VersionSource source) {
        requireAnyBoundary(start, end);
        return new VersionSelection(Mode.FAMILY_RANGE, requiredSource(source),
                null, start, end, null, null);
    }

    public static VersionSelection regex(String expression, VersionSource source) {
        if (expression == null || expression.isEmpty()) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb.version-regex 不能为空");
        }
        try {
            return new VersionSelection(Mode.REGEX, requiredSource(source),
                    null, null, null, expression, Pattern.compile(expression));
        } catch (PatternSyntaxException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb.version-regex 不是合法正则: " + e.getDescription(), e);
        }
    }

    public Mode mode() { return mode; }
    public VersionSource source() { return source; }
    public MigrationVersion target() { return target; }
    public MigrationVersion start() { return start; }
    public MigrationVersion end() { return end; }
    public String regex() { return regexText; }
    public boolean explicit() { return mode != Mode.ALL; }

    public boolean matches(ResolvedMigration migration) {
        if (mode == Mode.ALL) {
            return true;
        }
        MigrationVersion candidate = coordinate(migration);
        if (candidate == null) {
            return false;
        }
        switch (mode) {
            case EXACT:
                return candidate.equals(target);
            case RANGE:
                return inRange(candidate, start, end);
            case FAMILY:
                return candidate.isSameOrDescendantOf(target);
            case FAMILY_RANGE:
                return inFamilyRange(candidate, start, end);
            case REGEX:
                return regex.matcher(candidate.toString()).matches();
            default:
                return true;
        }
    }

    /** 精确、版本族和正则选择若一个本地版本都没有命中，给出明确错误。 */
    public void requireMatch(List<ResolvedMigration> resolved) {
        if (mode != Mode.EXACT && mode != Mode.FAMILY && mode != Mode.REGEX) {
            return;
        }
        for (ResolvedMigration migration : resolved) {
            if (migration.type() != MigrationType.UNDO_SQL && matches(migration)) {
                return;
            }
        }
        String expected = mode == Mode.REGEX ? regexText : String.valueOf(target);
        throw new FlydbException(ErrorCode.INVALID_VERSION,
                "本地迁移中不存在匹配的" + sourceLabel() + ": " + expected);
    }

    /**
     * range 按版本顺序比较边界，结束版本的族子版本（如 {@code 20260625.3} 相对
     * {@code 20260625}）数值上大于结束版本本身，会被静默排除。此处发现存在这种被
     * 排除的子版本时输出一次警告，提示改用 family-range。
     */
    public void warnFamilyDescendantsExcluded(List<ResolvedMigration> resolved, Log log) {
        if (mode != Mode.RANGE || end == null) {
            return;
        }
        List<MigrationVersion> excluded = new ArrayList<MigrationVersion>();
        for (ResolvedMigration migration : resolved) {
            if (migration.type() == MigrationType.UNDO_SQL) {
                continue;
            }
            MigrationVersion candidate = coordinate(migration);
            if (candidate == null) {
                continue;
            }
            if (candidate.compareTo(end) > 0 && candidate.isSameOrDescendantOf(end)
                    && !excluded.contains(candidate)) {
                excluded.add(candidate);
            }
        }
        if (excluded.isEmpty()) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        int shown = Math.min(excluded.size(), 5);
        for (int i = 0; i < shown; i++) {
            if (i > 0) detail.append("、");
            detail.append(excluded.get(i));
        }
        if (excluded.size() > shown) {
            detail.append(" 等 ").append(excluded.size()).append(" 个");
        }
        log.warn("range 结束版本 " + end + " 不含其族子版本: " + detail
                + "；如需包含请改用 family-range（CLI: --version-selection family-range）");
    }

    private MigrationVersion coordinate(ResolvedMigration migration) {
        return source == VersionSource.DIRECTORY
                ? migration.directoryVersion() : migration.version();
    }

    private String sourceLabel() {
        return source == VersionSource.DIRECTORY ? "目录版本" : "文件版本";
    }

    private static boolean inRange(MigrationVersion value, MigrationVersion start,
                                   MigrationVersion end) {
        return (start == null || value.compareTo(start) >= 0)
                && (end == null || value.compareTo(end) <= 0);
    }

    private static boolean inFamilyRange(MigrationVersion value, MigrationVersion start,
                                         MigrationVersion end) {
        boolean aboveStart = start == null || value.compareTo(start) >= 0;
        boolean belowEnd = end == null || value.compareTo(end) <= 0
                || value.isSameOrDescendantOf(end);
        return aboveStart && belowEnd;
    }

    private static VersionSource requiredSource(VersionSource source) {
        if (source == null) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb.version-source 不能为空");
        }
        return source;
    }

    private static MigrationVersion requiredVersion(MigrationVersion version, String key) {
        if (version == null) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb." + key + " 不能为空");
        }
        return version;
    }

    private static void requireAnyBoundary(MigrationVersion start, MigrationVersion end) {
        if (start == null && end == null) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb.start-version/flydb.end-version 至少配置一个");
        }
    }
}
