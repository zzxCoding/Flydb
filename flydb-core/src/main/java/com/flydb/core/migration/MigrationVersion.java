package com.flydb.core.migration;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/**
 * 迁移版本号（不可变值对象，设计 02 §3）。
 *
 * <p>版本号 = 一段或多段非负整数，以 {@code .} 分隔（{@code 1}、{@code 1.2}、{@code 20260812.1}）。
 * 段值用 {@link BigInteger} 存储，不对数值范围做隐藏假设（修复旧原型 {@code Integer.parseInt} 缺陷 #6）。
 *
 * <p>核心语义：末尾补零不改版本（{@code 1.2 ≡ 1.2.0}）；{@code equals}/{@code hashCode}/
 * {@code compareTo} 三者严格一致，避免放入 {@code HashSet} 时判重失败；段值按<b>数值</b>比较
 * （故 {@code 2 < 10}，而非字典序）。非法输入抛 {@link ErrorCode#INVALID_VERSION}（FLYDB-2001）。
 */
public final class MigrationVersion implements Comparable<MigrationVersion> {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("\\d+");

    private final List<BigInteger> parts;
    private final String rawValue;

    private MigrationVersion(List<BigInteger> parts, String rawValue) {
        this.parts = Collections.unmodifiableList(new ArrayList<BigInteger>(parts));
        this.rawValue = rawValue;
    }

    /**
     * 解析版本号文本。每段必须匹配 {@code \d+}，否则抛 FLYDB-2001。
     *
     * @param versionText 版本号原文，如 {@code "1.2"}；不可为 null 或空。
     */
    public static MigrationVersion parse(String versionText) {
        if (versionText == null || versionText.isEmpty()) {
            throw new FlydbException(ErrorCode.INVALID_VERSION, "版本号为空");
        }
        String[] segments = versionText.split("\\.", -1);
        List<BigInteger> parsed = new ArrayList<BigInteger>(segments.length);
        for (String segment : segments) {
            if (!SEGMENT_PATTERN.matcher(segment).matches()) {
                throw new FlydbException(ErrorCode.INVALID_VERSION,
                        "无法解析版本号（应为以点分隔的非负整数段）: " + versionText);
            }
            parsed.add(new BigInteger(segment));
        }
        return new MigrationVersion(parsed, versionText);
    }

    @Override
    public int compareTo(MigrationVersion other) {
        int max = Math.max(this.parts.size(), other.parts.size());
        for (int i = 0; i < max; i++) {
            BigInteger left = i < this.parts.size() ? this.parts.get(i) : BigInteger.ZERO;
            BigInteger right = i < other.parts.size() ? other.parts.get(i) : BigInteger.ZERO;
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
        // 跳过尾部零段，保证 1.2 / 1.2.0 / 1.2.0.0 哈希一致（与 equals 语义对齐）。
        int last = parts.size();
        while (last > 0 && parts.get(last - 1).signum() == 0) {
            last--;
        }
        return parts.subList(0, last).hashCode();
    }

    @Override
    public String toString() {
        return rawValue;
    }
}
