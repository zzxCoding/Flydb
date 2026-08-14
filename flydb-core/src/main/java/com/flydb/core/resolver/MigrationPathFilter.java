package com.flydb.core.resolver;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 基于规范化相对路径的 glob/regex 交集过滤器。 */
final class MigrationPathFilter {

    private final Pattern directory;
    private final Pattern file;
    private final Pattern path;

    private MigrationPathFilter(Pattern directory, Pattern file, Pattern path) {
        this.directory = directory;
        this.file = file;
        this.path = path;
    }

    static MigrationPathFilter from(ResolverContext context) {
        return new MigrationPathFilter(
                oneDimension("directory", context.directoryGlob(), context.directoryRegex()),
                oneDimension("file", context.fileGlob(), context.fileRegex()),
                oneDimension("path", context.pathGlob(), context.pathRegex()));
    }

    boolean matches(String relativePath) {
        String fileName = fileName(relativePath);
        String parent = parent(relativePath);
        return (directory == null || directory.matcher(parent).matches())
                && (file == null || file.matcher(fileName).matches())
                && (path == null || path.matcher(relativePath).matches());
    }

    private static Pattern oneDimension(String dimension, String glob, String regex) {
        glob = emptyToNull(glob);
        regex = emptyToNull(regex);
        if (glob != null && regex != null) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb." + dimension + "-glob 与 flydb." + dimension
                            + "-regex 不可同时配置");
        }
        if (glob != null) {
            return Pattern.compile(globToRegex(glob));
        }
        if (regex != null) {
            try {
                return Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb." + dimension + "-regex 不是合法正则: "
                                + e.getDescription(), e);
            }
        }
        return null;
    }

    /**
     * 跨平台的最小 glob：{@code *}/{@code ?} 不跨目录，{@code **} 可跨目录，
     * 末尾 {@code /**} 同时匹配目录本身及全部后代。
     */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '/' && i + 2 < glob.length()
                    && glob.charAt(i + 1) == '*' && glob.charAt(i + 2) == '*'
                    && i + 3 == glob.length()) {
                regex.append("(?:/.*)?");
                i += 2;
            } else if (c == '*' && i + 2 < glob.length()
                    && glob.charAt(i + 1) == '*' && glob.charAt(i + 2) == '/') {
                regex.append("(?:.*/)?");
                i += 2;
            } else if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
            }
        }
        return regex.append('$').toString();
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
