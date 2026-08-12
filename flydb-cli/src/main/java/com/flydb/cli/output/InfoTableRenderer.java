package com.flydb.cli.output;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Map;

import com.flydb.core.api.MigrationInfoService;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationState;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

/** 将 MigrationInfoService 渲染为中文友好的定宽表格。 */
public final class InfoTableRenderer {

    private static final Map<MigrationState, String> STATE_NAMES = stateNames();

    public String render(String flydbVersion, String database, String url, String historyTable,
                         MigrationInfoService information, boolean color) {
        StringBuilder output = new StringBuilder();
        output.append("flydb ").append(flydbVersion).append(" · ").append(database)
                .append(" · ").append(url).append(" · 历史表: ").append(historyTable)
                .append('\n').append('\n');
        row(output, "版本", "描述", "类型", "已安装时间", "耗时(ms)", "状态");
        row(output, "----------", "----------------------", "------",
                "-------------------", "--------", "--------");
        for (MigrationInfo info : information.all()) appendMigration(output, info, color);
        return output.toString();
    }

    private static void appendMigration(StringBuilder output, MigrationInfo info, boolean color) {
        ResolvedMigration resolved = info.resolved();
        AppliedMigration applied = info.applied();
        MigrationVersion version = resolved != null ? resolved.version()
                : applied == null ? null : applied.version();
        String versionText = version == null ? "(可重复)" : version.toString();
        String description = resolved != null ? resolved.description()
                : applied == null ? "" : applied.description();
        MigrationType type = resolved != null ? resolved.type()
                : applied == null ? MigrationType.SQL : applied.type();
        String installedOn = applied == null || applied.installedOn() == null ? "-"
                : formatDate(applied.installedOn());
        String executionTime = applied == null ? "-" : String.valueOf(applied.executionTimeMillis());
        String state = STATE_NAMES.get(info.state());
        if (color) state = color(info.state(), state);
        row(output, versionText, description, typeName(type), installedOn, executionTime, state);
    }

    private static void row(StringBuilder output, String version, String description,
                            String type, String installedOn, String executionTime, String state) {
        output.append(pad(version, 10)).append("  ")
                .append(pad(description, 22)).append("  ")
                .append(pad(type, 6)).append("  ")
                .append(pad(installedOn, 19)).append("  ")
                .append(pad(executionTime, 8)).append("  ")
                .append(state).append('\n');
    }

    private static String pad(String value, int width) {
        String text = value == null ? "" : value;
        StringBuilder result = new StringBuilder(text);
        for (int i = displayWidth(text); i < width; i++) result.append(' ');
        return result.toString();
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            width += isWide(codePoint) ? 2 : 1;
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static String formatDate(Date date) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }

    private static String typeName(MigrationType type) {
        if (type == MigrationType.BASELINE) return "基准";
        if (type == MigrationType.UNDO_SQL) return "UNDO";
        return type.name();
    }

    private static String color(MigrationState state, String text) {
        String code;
        if (state == MigrationState.SUCCESS || state == MigrationState.BASELINE) code = "32";
        else if (state == MigrationState.FAILED) code = "31";
        else if (state == MigrationState.MISSING || state == MigrationState.FUTURE) code = "90";
        else code = "33";
        return "\u001B[" + code + "m" + text + "\u001B[0m";
    }

    private static Map<MigrationState, String> stateNames() {
        Map<MigrationState, String> names = new EnumMap<MigrationState, String>(MigrationState.class);
        names.put(MigrationState.PENDING, "待执行");
        names.put(MigrationState.SUCCESS, "成功");
        names.put(MigrationState.FAILED, "失败");
        names.put(MigrationState.MISSING, "缺失");
        names.put(MigrationState.OUT_OF_ORDER, "乱序");
        names.put(MigrationState.FUTURE, "未来版本");
        names.put(MigrationState.OUTDATED, "待更新");
        names.put(MigrationState.BASELINE, "基准");
        names.put(MigrationState.UNDONE, "已撤销");
        return names;
    }
}
