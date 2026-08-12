package com.flydb.core.placeholder;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 占位符替换器单测（设计 05 §9、08 §1）。
 *
 * <p>覆盖：基础替换、{@code $${} 转义、未定义占位符报 FLYDB-2009（含行号）、内置 {@code flydb:} 变量、
 * 可配置前后缀、无占位符原样返回。
 */
@DisplayName("PlaceholderReplacer")
class PlaceholderReplacerTest {

    private Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("基础替换")
    class BasicReplacement {

        @Test
        @DisplayName("替换单个已定义占位符")
        void replacesSingleDefinedPlaceholder() {
            Map<String, String> placeholders = map("table", "orders");
            String result = PlaceholderReplacer.replace(
                    "SELECT * FROM ${table};", "V1__init.sql",
                    "${", "}", placeholders);
            assertThat(result).isEqualTo("SELECT * FROM orders;");
        }

        @Test
        @DisplayName("替换同一脚本的多个占位符")
        void replacesMultiplePlaceholders() {
            Map<String, String> placeholders = map("a", "1", "b", "2");
            String result = PlaceholderReplacer.replace(
                    "${a} + ${b} = 3", "V1.sql", "${", "}", placeholders);
            assertThat(result).isEqualTo("1 + 2 = 3");
        }

        @Test
        @DisplayName("同一占位符多次出现全部替换")
        void replacesRepeatedPlaceholder() {
            Map<String, String> placeholders = map("x", "42");
            String result = PlaceholderReplacer.replace(
                    "${x} and ${x}", "V1.sql", "${", "}", placeholders);
            assertThat(result).isEqualTo("42 and 42");
        }

        @Test
        @DisplayName("无占位符的脚本原样返回")
        void returnsVerbatimWhenNoPlaceholder() {
            String result = PlaceholderReplacer.replace(
                    "CREATE TABLE t(id INT);", "V1.sql", "${", "}", map());
            assertThat(result).isEqualTo("CREATE TABLE t(id INT);");
        }

        @Test
        @DisplayName("值为空字符串的占位符替换为空")
        void replacesEmptyValuePlaceholder() {
            Map<String, String> placeholders = map("x", "");
            String result = PlaceholderReplacer.replace(
                    "[${x}]", "V1.sql", "${", "}", placeholders);
            assertThat(result).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("转义 $${")
    class Escape {

        @Test
        @DisplayName("$${ 转义为字面量 ${ 且不参与替换")
        void escapesToLiteralPrefix() {
            Map<String, String> placeholders = map("foo", "REPLACED");
            String result = PlaceholderReplacer.replace(
                    "$${foo} = ${foo}", "V1.sql", "${", "}", placeholders);
            assertThat(result).isEqualTo("${foo} = REPLACED");
        }

        @Test
        @DisplayName("转义后即使 key 未定义也不报错")
        void escapedUndefinedKeyDoesNotError() {
            String result = PlaceholderReplacer.replace(
                    "$${undefined}", "V1.sql", "${", "}", map());
            assertThat(result).isEqualTo("${undefined}");
        }

        @Test
        @DisplayName("未转义的 ${ 在 key 未定义时报 FLYDB-2009")
        void unescapedUndefinedKeyErrors() {
            assertThatThrownBy(() -> PlaceholderReplacer.replace(
                    "${nope}", "V1.sql", "${", "}", map()))
                    .isInstanceOf(FlydbException.class)
                    .hasMessageContaining("FLYDB-2009")
                    .hasMessageContaining("nope")
                    .hasMessageContaining("V1.sql");
        }
    }

    @Nested
    @DisplayName("未定义占位符 FLYDB-2009")
    class UndefinedPlaceholder {

        @Test
        @DisplayName("错误消息包含脚本名、占位符名与行号")
        void errorMessageIncludesScriptKeyAndLine() {
            assertThatThrownBy(() -> PlaceholderReplacer.replace(
                    "line1\nline2\n${missing} here", "V2__add.sql",
                    "${", "}", map("other", "1")))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> {
                        FlydbException fe = (FlydbException) ex;
                        assertThat(fe.errorCode()).isEqualTo(ErrorCode.UNDEFINED_PLACEHOLDER);
                        assertThat(fe.getMessage()).contains("V2__add.sql");
                        assertThat(fe.getMessage()).contains("missing");
                        assertThat(fe.getMessage()).contains("3"); // 第 3 行
                    });
        }

        @Test
        @DisplayName("第一行的未定义占位符报告行号 1")
        void firstLineReportsOne() {
            assertThatThrownBy(() -> PlaceholderReplacer.replace(
                    "${x}", "V1.sql", "${", "}", map()))
                    .isInstanceOf(FlydbException.class)
                    .hasMessageContaining("V1.sql");
        }
    }

    @Nested
    @DisplayName("内置 flydb: 变量")
    class BuiltInVariables {

        @Test
        @DisplayName("内置变量优先于用户占位符（同 key 时内置胜出）")
        void builtInsOverrideUserPlaceholders() {
            Map<String, String> builtIns = map("database", "MySQL");
            Map<String, String> placeholders = map("database", "USER");
            String result = PlaceholderReplacer.replace(
                    "${flydb:database}", "V1.sql", "${", "}",
                    placeholders, builtIns);
            assertThat(result).isEqualTo("MySQL");
        }

        @Test
        @DisplayName("flydb: 命名空间与用户占位符互不冲突")
        void flydbNamespaceDoesNotClashWithUserKeys() {
            Map<String, String> builtIns = map("schema", "public", "user", "root");
            Map<String, String> placeholders = map("schema", "mytable");
            String result = PlaceholderReplacer.replace(
                    "${flydb:schema}.${flydb:user} ${schema}",
                    "V1.sql", "${", "}", placeholders, builtIns);
            assertThat(result).isEqualTo("public.root mytable");
        }

        @Test
        @DisplayName("未定义的 flydb: 变量也报 FLYDB-2009")
        void undefinedBuiltInErrors() {
            assertThatThrownBy(() -> PlaceholderReplacer.replace(
                    "${flydb:nonexistent}", "V1.sql", "${", "}", map(), map()))
                    .isInstanceOf(FlydbException.class)
                    .hasMessageContaining("FLYDB-2009");
        }
    }

    @Nested
    @DisplayName("可配置前后缀")
    class CustomDelimiters {

        @Test
        @DisplayName("自定义前缀 #[ 与后缀 ]")
        void customPrefixAndSuffix() {
            Map<String, String> placeholders = map("v", "1");
            String result = PlaceholderReplacer.replace(
                    "#[v]", "V1.sql", "#[", "]", placeholders);
            assertThat(result).isEqualTo("1");
        }

        @Test
        @DisplayName("自定义前缀下 $$ 转义前缀生效")
        void escapeFollowsCustomPrefix() {
            // 转义 = '$' + 前缀
            Map<String, String> placeholders = map("v", "1");
            String result = PlaceholderReplacer.replace(
                    "$#[v] = #[v]", "V1.sql", "#[", "]", placeholders);
            assertThat(result).isEqualTo("#[v] = 1");
        }
    }

    @Nested
    @DisplayName("边界与参数校验")
    class EdgeCases {

        @Test
        @DisplayName("未闭合的 ${ 原样保留（不报错、不替换）")
        void unterminatedPrefixKeptVerbatim() {
            String result = PlaceholderReplacer.replace(
                    "SELECT ${oops", "V1.sql", "${", "}", map("oops", "1"));
            assertThat(result).isEqualTo("SELECT ${oops");
        }

        @Test
        @DisplayName("sql 为 null 抛 NPE")
        void nullSqlThrowsNpe() {
            assertThatThrownBy(() -> PlaceholderReplacer.replace(
                    null, "V1.sql", "${", "}", map()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("scriptName 为 null 抛 NPE")
        void nullScriptNameThrowsNpe() {
            assertThatThrownBy(() -> PlaceholderReplacer.replace(
                    "${x}", null, "${", "}", map("x", "1")))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("空前后缀抛 IllegalArgumentException")
        void emptyDelimitersThrow() {
            assertThatThrownBy(() -> PlaceholderReplacer.replace(
                    "${x}", "V1.sql", "", "}", map("x", "1")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
