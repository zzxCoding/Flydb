package com.flydb.core.migration;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/**
 * 迁移版本号（不可变值对象，设计 02 §3）。
 *
 * <p>版本号以数字开头，由字母数字段和 {@code .}/{@code _}/{@code -} 分隔符组成，
 * 例如 {@code 1}、{@code 1.2}、{@code 20260327-b06.4}。数字 token 用 {@link BigInteger}
 * 存储，不对数值范围做隐藏假设（修复旧原型 {@code Integer.parseInt} 缺陷 #6）。
 *
 * <p>核心语义：末尾补零不改版本（{@code 1.2 ≡ 1.2.0}）；{@code equals}/{@code hashCode}/
 * {@code compareTo} 三者严格一致，避免放入 {@code HashSet} 时判重失败；数字 token 按数值比较，
 * 字母 token 按不区分大小写的字典序比较，数字 token 排在字母 token 前。非法输入抛
 * {@link ErrorCode#INVALID_VERSION}（FLYDB-2001）。
 */
public final class MigrationVersion implements Comparable<MigrationVersion> {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "\\d[A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)*");

    private final List<Part> parts;
    private final String rawValue;

    private MigrationVersion(List<Part> parts, String rawValue) {
        this.parts = normalize(parts);
        this.rawValue = rawValue;
    }

    /**
     * 解析版本号文本。版本必须以数字开头，字母数字段可用点、下划线或连字符分隔。
     *
     * @param versionText 版本号原文，如 {@code "1.2"}；不可为 null 或空。
     */
    public static MigrationVersion parse(String versionText) {
        if (versionText == null || versionText.isEmpty()) {
            throw new FlydbException(ErrorCode.INVALID_VERSION, "版本号为空");
        }
        if (!VERSION_PATTERN.matcher(versionText).matches()) {
            throw new FlydbException(ErrorCode.INVALID_VERSION,
                    "无法解析版本号（应以数字开头，字母数字段可用 .、_ 或 - 分隔）: "
                            + versionText);
        }
        List<Part> parsed = new ArrayList<Part>();
        StringBuilder token = new StringBuilder();
        Boolean numeric = null;
        for (int i = 0; i < versionText.length(); i++) {
            char current = versionText.charAt(i);
            if (current == '.' || current == '_' || current == '-') {
                addPart(parsed, token, numeric);
                token.setLength(0);
                numeric = null;
                continue;
            }
            boolean currentNumeric = Character.isDigit(current);
            if (numeric != null && numeric.booleanValue() != currentNumeric) {
                addPart(parsed, token, numeric);
                token.setLength(0);
            }
            token.append(current);
            numeric = Boolean.valueOf(currentNumeric);
        }
        addPart(parsed, token, numeric);
        return new MigrationVersion(parsed, versionText);
    }

    /**
     * 当前版本是否等于给定版本族，或是它的子版本。
     * 例如 {@code 20230531.2} 和 {@code 20230531-b06.4} 属于 {@code 20230531}，
     * 但 {@code 202305310.2} 不属于。
     */
    public boolean isSameOrDescendantOf(MigrationVersion family) {
        if (family == null) {
            return false;
        }
        if (equals(family)) {
            return true;
        }
        if (parts.size() <= family.parts.size()) {
            return false;
        }
        for (int i = 0; i < family.parts.size(); i++) {
            if (!parts.get(i).equals(family.parts.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(MigrationVersion other) {
        int max = Math.max(this.parts.size(), other.parts.size());
        for (int i = 0; i < max; i++) {
            Part left = i < this.parts.size() ? this.parts.get(i) : Part.ZERO;
            Part right = i < other.parts.size() ? other.parts.get(i) : Part.ZERO;
            int segmentCmp = left.compareTo(right);
            if (segmentCmp != 0) {
                return segmentCmp;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MigrationVersion)) {
            return false;
        }
        return compareTo((MigrationVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return parts.hashCode();
    }

    @Override
    public String toString() {
        return rawValue;
    }

    private static void addPart(List<Part> parts, StringBuilder token, Boolean numeric) {
        if (numeric == null || token.length() == 0) {
            return;
        }
        parts.add(numeric.booleanValue()
                ? Part.numeric(new BigInteger(token.toString()))
                : Part.text(token.toString()));
    }

    private static List<Part> normalize(List<Part> parsed) {
        int last = parsed.size();
        while (last > 1 && parsed.get(last - 1).isNumericZero()) {
            last--;
        }
        return Collections.unmodifiableList(new ArrayList<Part>(parsed.subList(0, last)));
    }

    private static final class Part implements Comparable<Part> {
        static final Part ZERO = numeric(BigInteger.ZERO);

        private final BigInteger number;
        private final String text;

        private Part(BigInteger number, String text) {
            this.number = number;
            this.text = text;
        }

        static Part numeric(BigInteger value) {
            return new Part(value, null);
        }

        static Part text(String value) {
            return new Part(null, value.toLowerCase(Locale.ROOT));
        }

        boolean isNumericZero() {
            return number != null && number.signum() == 0;
        }

        @Override
        public int compareTo(Part other) {
            if (number != null && other.number != null) {
                return number.compareTo(other.number);
            }
            if (text != null && other.text != null) {
                return text.compareTo(other.text);
            }
            return number != null ? -1 : 1;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Part && compareTo((Part) value) == 0;
        }

        @Override
        public int hashCode() {
            return number != null ? 31 + number.hashCode() : 37 + text.hashCode();
        }
    }
}
