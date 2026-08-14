package com.flydb.core.resolver;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationVersion;

/** 从规范化相对父目录中提取离文件最近的目录版本。 */
final class DirectoryVersionExtractor {

    private final Pattern pattern;

    private DirectoryVersionExtractor(Pattern pattern) {
        this.pattern = pattern;
    }

    static DirectoryVersionExtractor compile(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb.directory-version-regex 不能为空");
        }
        try {
            Pattern pattern = Pattern.compile(expression);
            if (pattern.matcher("").groupCount() == 0) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.directory-version-regex 必须提供名为 version 或第一个捕获组");
            }
            return new DirectoryVersionExtractor(pattern);
        } catch (PatternSyntaxException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "flydb.directory-version-regex 不是合法正则: "
                            + e.getDescription(), e);
        }
    }

    MigrationVersion extract(String relativeScript) {
        String directory = parent(relativeScript);
        Matcher matcher = pattern.matcher(directory);
        String value = null;
        while (matcher.find()) {
            value = capturedVersion(matcher);
        }
        if (value == null || value.isEmpty()) {
            throw new FlydbException(ErrorCode.INVALID_VERSION,
                    "无法从迁移目录提取版本: " + directory
                            + "（脚本 " + relativeScript + "）");
        }
        try {
            return MigrationVersion.parse(value);
        } catch (FlydbException e) {
            throw new FlydbException(ErrorCode.INVALID_VERSION,
                    "脚本 " + relativeScript + " 提取到非法目录版本: " + value, e);
        }
    }

    private static String capturedVersion(Matcher matcher) {
        try {
            return matcher.group("version");
        } catch (IllegalArgumentException noNamedGroup) {
            if (matcher.groupCount() < 1) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "flydb.directory-version-regex 必须提供名为 version 或第一个捕获组");
            }
            return matcher.group(1);
        }
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
