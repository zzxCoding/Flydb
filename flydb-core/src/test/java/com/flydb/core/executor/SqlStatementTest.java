package com.flydb.core.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link SqlStatement} 值对象契约：构造校验、getter、equals/hashCode/toString。 */
@DisplayName("SqlStatement")
class SqlStatementTest {

    @Test
    @DisplayName("getter 返回构造值")
    void getters() {
        SqlStatement s = new SqlStatement("SELECT 1", 7);
        assertThat(s.sql()).isEqualTo("SELECT 1");
        assertThat(s.lineNumber()).isEqualTo(7);
    }

    @Test
    @DisplayName("null sql → NullPointerException")
    void nullSqlRejected() {
        assertThatThrownBy(() -> new SqlStatement(null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("lineNumber < 1 → IllegalArgumentException")
    void nonPositiveLineRejected() {
        assertThatThrownBy(() -> new SqlStatement("SELECT 1", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lineNumber");
    }

    @Test
    @DisplayName("相同 sql + 行号 → equals/hashCode 一致")
    void equalsHashCode() {
        SqlStatement a = new SqlStatement("SELECT 1", 1);
        SqlStatement b = new SqlStatement("SELECT 1", 1);
        SqlStatement c = new SqlStatement("SELECT 2", 1);
        SqlStatement d = new SqlStatement("SELECT 1", 2);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo(d);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a statement");
        assertThat(a).isEqualTo(a);   // 自反
    }

    @Test
    @DisplayName("toString 含行号与 sql")
    void toStringContainsFields() {
        SqlStatement s = new SqlStatement("SELECT 1", 3);
        String str = s.toString();
        assertThat(str).contains("3").contains("SELECT 1");
    }
}
