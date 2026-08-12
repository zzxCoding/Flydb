package com.flydb.core.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/**
 * MigrationVersion 表驱动测试（设计 02 §3、08 §1）。
 *
 * <p>核心不变量：{@code 1.2 ≡ 1.2.0}（末尾补零不改语义）；{@code equals}/{@code hashCode}/
 * {@code compareTo} 三者严格一致；段值按数值比较（故 2 &lt; 10，而非字典序）；非法输入报 FLYDB-2001。
 */
class MigrationVersionTest {

    // ---- parse + toString 回环 ----

    @ParameterizedTest
    @CsvSource({
            "1,           1",
            "1.2,         1.2",
            "1.2.3,       1.2.3",
            "20260812.1,  20260812.1",
            "0,           0",
    })
    void parse_round_trips_to_raw_value(String input, String expected) {
        assertThat(MigrationVersion.parse(input).toString()).isEqualTo(expected);
    }

    @Test
    void big_integer_sized_versions_supported() {
        // 旧原型用 Integer.parseInt 会溢出；BigInteger 不对数值范围做隐藏假设（设计 02 §3）。
        MigrationVersion huge = MigrationVersion.parse("99999999999999999999.1");
        assertThat(huge.toString()).isEqualTo("99999999999999999999.1");
    }

    // ---- 末尾补零等价性：1.2 ≡ 1.2.0 ----

    @Test
    void trailing_zeros_are_semantically_equal() {
        MigrationVersion a = MigrationVersion.parse("1.2");
        MigrationVersion b = MigrationVersion.parse("1.2.0");
        MigrationVersion c = MigrationVersion.parse("1.2.0.0");

        assertThat(a.compareTo(b)).isZero();
        assertThat(a.compareTo(c)).isZero();
        assertThat(b.compareTo(c)).isZero();
    }

    @Test
    void equalsAndHashCode_treat_trailing_zeros_as_same() {
        MigrationVersion a = MigrationVersion.parse("1.2");
        MigrationVersion b = MigrationVersion.parse("1.2.0.0");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        // 放入 HashSet 判重——三者归一
        Set<MigrationVersion> set = new HashSet<MigrationVersion>();
        set.add(MigrationVersion.parse("1.2"));
        set.add(MigrationVersion.parse("1.2.0"));
        set.add(MigrationVersion.parse("1.2.0.0"));
        assertThat(set).hasSize(1);
    }

    // ---- 数值比较（非字典序）----

    @ParameterizedTest
    @CsvSource({
            "1.2,    1.10,   -1",   // 段值 2 < 10（不是字典序 '2' > '1'）
            "2,      10,     -1",   // 整段 2 < 10
            "1.10,   1.2,    1",
            "10,     2,      1",
            "1.2,    1.2.1,  -1",   // 缺失段按 0：1.2.0 < 1.2.1
            "1.2.1,  1.2,    1",
            "1.2,    1.2,    0",
            "20260812.1, 20260812.2, -1",
            "20260812.10, 20260812.2, 1",
    })
    void compareTo_is_numeric_and_pads_missing_segments_with_zero(String left, String right, int sign) {
        int actual = MigrationVersion.parse(left).compareTo(MigrationVersion.parse(right));
        assertThat(Integer.signum(actual)).isEqualTo(sign);
    }

    @Test
    void equals_consistent_with_compareTo() {
        MigrationVersion a = MigrationVersion.parse("1.0");
        MigrationVersion b = MigrationVersion.parse("1");
        assertThat(a.equals(b)).isTrue();
        assertThat(a.compareTo(b)).isZero();
    }

    // ---- 非法输入 → FLYDB-2001 ----

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "1.", ".1", "1..2", "1.a", "a", "1.2.x", "-1", "1 2",
            "1,2", "v1", "1.2-3", "+1"
    })
    void invalid_versions_throw_flydb_2001(String invalid) {
        assertThatThrownBy(() -> MigrationVersion.parse(invalid))
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERSION);
    }

    @Test
    void null_input_throws_flydb_2001_not_npe() {
        assertThatThrownBy(() -> MigrationVersion.parse(null))
                .isInstanceOf(FlydbException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_VERSION);
    }
}
