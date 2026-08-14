package com.flydb.cli.output;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.flydb.core.api.MigrationInfoService;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationState;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

/** 将 MigrationInfoService 渲染为中文友好的对齐表格；列宽按内容自适应，宽版本号不会挤歪后续列。 */
public final class InfoTableRenderer {

    private static final Map<MigrationState, String> STATE_NAMES = stateNames();

    /** 各列保底宽度：内容不足时维持既有紧凑布局。 */
    private static final int[] MIN_WIDTHS = {10, 22, 6, 19, 8};

    public String render(String flydbVersion, String database, String url, String historyTable,
                         MigrationInfoService information, boolean color) {
        List<String[]> rows = new ArrayList<String[]>();
        for (MigrationInfo info : information.all()) {
            rows.add(cells(info, color));
        }
        int[] widths = columnWidths(rows);

        StringBuilder output = new StringBuilder();
        output.append("flydb ").append(flydbVersion).append(" · ").append(database)
                .append(" · ").append(url).append(" · 历史表: ").append(historyTable)
                .append('\n').append('\n');
        row(output, cells("版本", "描述", "类型", "已安装时间", "耗时(ms)", "状态"), widths);
        row(output, dashes(widths), widths);
        for (String[] cells : rows) {
            row(output, cells, widths);
        }
        return output.toString();
    }

    /** 第一遍：收集每行文本（状态列带 ANSI 颜色码，不参与定宽）。 */
    private static String[] cells(MigrationInfo info, boolean color) {
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
        return cells(versionText, description, typeName(type), installedOn, executionTime, state);
    }

    private static String[] cells(String version, String description, String type,
                                  String installedOn, String executionTime, String state) {
        return new String[]{version, description, type, installedOn, executionTime, state};
    }

    private static int[] columnWidths(List<String[]> rows) {
        int[] widths = new int[MIN_WIDTHS.length];
        for (int i = 0; i < MIN_WIDTHS.length; i++) {
            int width = MIN_WIDTHS[i];
            width = Math.max(width, displayWidth(headerText(i)));
            for (String[] cells : rows) {
                width = Math.max(width, displayWidth(cells[i]));
            }
            widths[i] = width;
        }
        return widths;
    }

    private static String headerText(int column) {
        switch (column) {
            case 0: return "版本";
            case 1: return "描述";
            case 2: return "类型";
            case 3: return "已安装时间";
            default: return "耗时(ms)";
        }
    }

    private static String[] dashes(int[] widths) {
        String[] dashes = new String[widths.length + 1];
        for (int i = 0; i < widths.length; i++) {
            StringBuilder dash = new StringBuilder();
            for (int j = 0; j < widths[i]; j++) dash.append('-');
            dashes[i] = dash.toString();
        }
        dashes[widths.length] = "--------";
        return dashes;
    }

    /** 第二遍：按计算宽度渲染；状态列固定在最后，不定宽。 */
    private static void row(StringBuilder output, String[] cells, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            output.append(pad(cells[i], widths[i])).append("  ");
        }
        output.append(cells[widths.length]).append('\n');
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
