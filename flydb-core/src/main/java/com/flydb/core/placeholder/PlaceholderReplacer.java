package com.flydb.core.placeholder;

import java.util.Collections;
import java.util.Map;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/**
 * 占位符替换器（设计 05 §9）。
 *
 * <p>替换发生在 checksum 计算<b>之后</b>、词法解析<b>之前</b>（02 §7）。语法 {@code ${key}}，
 * 前后缀可配置；未定义占位符抛 {@link ErrorCode#UNDEFINED_PLACEHOLDER}（FLYDB-2009，含脚本名与行号），
 * 绝不静默保留原文——静默是配置错误的温床。
 *
 * <p>转义：在前缀前再写一个美元符号（默认写作 `$${`）→ 字面量前缀，不参与替换。
 * {@code flydb:} 命名空间内置变量优先于用户占位符，避免与用户键冲突。
 *
 * <p>本类为纯函数，无状态、无副作用，线程安全。
 */
public final class PlaceholderReplacer {

    private PlaceholderReplacer() {
    }

    /** 无内置变量便捷重载。 */
    public static String replace(String sql, String scriptName,
                                 String prefix, String suffix,
                                 Map<String, String> placeholders) {
        return replace(sql, scriptName, prefix, suffix, placeholders,
                Collections.<String, String>emptyMap());
    }

    /**
     * 替换 {@code sql} 中的占位符。
     *
     * @param sql          脚本原文，不可为 null
     * @param scriptName   脚本名（用于错误定位），不可为 null
     * @param prefix       占位符前缀，如 {@code "${"}，不可为空
     * @param suffix       占位符后缀，如 {@code "}"}，不可为空
     * @param placeholders 用户占位符映射，不可为 null（键不含前后缀）
     * @param builtIns     内置变量映射（{@code flydb:} 命名空间），不可为 null；同键时优先于用户占位符
     * @return 替换后的脚本
     * @throws FlydbException(FLYDB-2009) 引用了未定义的占位符（详情含脚本名、行号、占位符名）
     */
    public static String replace(String sql, String scriptName,
                                 String prefix, String suffix,
                                 Map<String, String> placeholders,
                                 Map<String, String> builtIns) {
        if (sql == null) {
            throw new NullPointerException("sql");
        }
        if (scriptName == null) {
            throw new NullPointerException("scriptName");
        }
        if (prefix == null || prefix.isEmpty() || suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("prefix/suffix 不可为空");
        }
        if (placeholders == null) {
            throw new NullPointerException("placeholders");
        }
        if (builtIns == null) {
            throw new NullPointerException("builtIns");
        }

        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        while (i < len) {
            // 转义：'$' 紧跟前缀 → 字面量前缀（默认 $${ → ${）
            if (sql.charAt(i) == '$'
                    && startsAt(sql, prefix, i + 1)) {
                out.append(prefix);
                i += 1 + prefix.length();
                continue;
            }
            // 占位符：前缀 ... 后缀
            if (startsAt(sql, prefix, i)) {
                int keyStart = i + prefix.length();
                int suf = sql.indexOf(suffix, keyStart);
                if (suf < 0) {
                    // 未闭合：原样保留前缀，继续扫描（不报错、不替换）
                    out.append(prefix);
                    i += prefix.length();
                    continue;
                }
                String key = sql.substring(keyStart, suf);
                String value = lookup(scriptName, sql, i, key, placeholders, builtIns);
                out.append(value);
                i = suf + suffix.length();
                continue;
            }
            out.append(sql.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static final String FLYDB_NAMESPACE = "flydb:";

    private static String lookup(String scriptName, String sql, int prefixPos,
                                 String key, Map<String, String> placeholders,
                                 Map<String, String> builtIns) {
        // flydb: 命名空间内置变量：去前缀后在内置表中查找（优先于用户占位符）
        if (key.startsWith(FLYDB_NAMESPACE)) {
            String builtInKey = key.substring(FLYDB_NAMESPACE.length());
            if (builtIns.containsKey(builtInKey)) {
                return builtIns.get(builtInKey);
            }
        } else if (placeholders.containsKey(key)) {
            return placeholders.get(key);
        }
        int line = lineNumber(sql, prefixPos);
        throw new FlydbException(ErrorCode.UNDEFINED_PLACEHOLDER,
                "脚本 " + scriptName + " 第 " + line + " 行 占位符 " + key
                        + " 未定义（检查 flydb.placeholders.* 配置）");
    }

    /** 1-based 行号：prefixPos 之前（含）的换行符数 + 1。 */
    private static int lineNumber(String sql, int prefixPos) {
        int line = 1;
        for (int i = 0; i < prefixPos; i++) {
            if (sql.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static boolean startsAt(String s, String token, int offset) {
        if (offset < 0 || offset + token.length() > s.length()) {
            return false;
        }
        for (int k = 0; k < token.length(); k++) {
            if (s.charAt(offset + k) != token.charAt(k)) {
                return false;
            }
        }
        return true;
    }
}
