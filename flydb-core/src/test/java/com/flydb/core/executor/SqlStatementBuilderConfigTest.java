package com.flydb.core.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SqlStatementBuilderConfig} 家族预设、Builder 与 {@link OraclePlsqlBlockDetector} 测试。
 *
 * <p>验证"差异表达为配置数据"：三家族预设字段互不相同；探测器正则覆盖 CREATE/DECLARE/BEGIN 家族。
 */
@DisplayName("SqlStatementBuilderConfig / OraclePlsqlBlockDetector")
class SqlStatementBuilderConfigTest {

    @Nested
    @DisplayName("三家族预设")
    class FamilyPresets {

        @Test
        @DisplayName("PostgreSQL：\" 引号、dollar-quoting、无反斜杠/#/DELIMITER/PL-SQL")
        void postgresql() {
            SqlStatementBuilderConfig pg = SqlStatementBuilderConfig.postgresql();
            assertThat(pg.identifierQuoteChar()).isEqualTo('"');
            assertThat(pg.dollarQuotingSupported()).isTrue();
            assertThat(pg.backslashEscapesSupported()).isFalse();
            assertThat(pg.hashLineCommentSupported()).isFalse();
            assertThat(pg.delimiterDirectiveSupported()).isFalse();
            assertThat(pg.plsqlBlockDetector()).isNull();
            assertThat(pg.defaultStatementSeparator()).isEqualTo(";");
        }

        @Test
        @DisplayName("MySQL：` 引号、反斜杠转义、# 注释、DELIMITER 指令、无 dollar-quoting/PL-SQL")
        void mysql() {
            SqlStatementBuilderConfig my = SqlStatementBuilderConfig.mysql();
            assertThat(my.identifierQuoteChar()).isEqualTo('`');
            assertThat(my.dollarQuotingSupported()).isFalse();
            assertThat(my.backslashEscapesSupported()).isTrue();
            assertThat(my.hashLineCommentSupported()).isTrue();
            assertThat(my.delimiterDirectiveSupported()).isTrue();
            assertThat(my.plsqlBlockDetector()).isNull();
            assertThat(my.defaultStatementSeparator()).isEqualTo(";");
        }

        @Test
        @DisplayName("Oracle：\" 引号、PL-SQL 探测器、无 dollar-quoting/反斜杠/#/DELIMITER")
        void oracle() {
            SqlStatementBuilderConfig ora = SqlStatementBuilderConfig.oracle();
            assertThat(ora.identifierQuoteChar()).isEqualTo('"');
            assertThat(ora.dollarQuotingSupported()).isFalse();
            assertThat(ora.backslashEscapesSupported()).isFalse();
            assertThat(ora.hashLineCommentSupported()).isFalse();
            assertThat(ora.delimiterDirectiveSupported()).isFalse();
            assertThat(ora.plsqlBlockDetector()).isSameAs(OraclePlsqlBlockDetector.INSTANCE);
            assertThat(ora.defaultStatementSeparator()).isEqualTo(";");
        }

        @Test
        @DisplayName("预设为不可变单例：多次获取同实例")
        void presetsAreCachedSingletons() {
            assertThat(SqlStatementBuilderConfig.postgresql()).isSameAs(SqlStatementBuilderConfig.postgresql());
            assertThat(SqlStatementBuilderConfig.mysql()).isSameAs(SqlStatementBuilderConfig.mysql());
            assertThat(SqlStatementBuilderConfig.oracle()).isSameAs(SqlStatementBuilderConfig.oracle());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderContract {

        @Test
        @DisplayName("Builder 默认值：\" 引号、分隔符 ;、布尔项 false、探测器 null")
        void builderDefaults() {
            SqlStatementBuilderConfig c = SqlStatementBuilderConfig.builder().build();
            assertThat(c.identifierQuoteChar()).isEqualTo('"');
            assertThat(c.dollarQuotingSupported()).isFalse();
            assertThat(c.backslashEscapesSupported()).isFalse();
            assertThat(c.hashLineCommentSupported()).isFalse();
            assertThat(c.delimiterDirectiveSupported()).isFalse();
            assertThat(c.plsqlBlockDetector()).isNull();
            assertThat(c.defaultStatementSeparator()).isEqualTo(";");
        }

        @Test
        @DisplayName("Builder 各 setter 生效")
        void builderSetters() {
            PlsqlBlockDetector detector = s -> false;
            SqlStatementBuilderConfig c = SqlStatementBuilderConfig.builder()
                    .identifierQuoteChar('`')
                    .dollarQuotingSupported(true)
                    .backslashEscapesSupported(true)
                    .hashLineCommentSupported(true)
                    .delimiterDirectiveSupported(true)
                    .plsqlBlockDetector(detector)
                    .defaultStatementSeparator("//")
                    .build();
            assertThat(c.identifierQuoteChar()).isEqualTo('`');
            assertThat(c.dollarQuotingSupported()).isTrue();
            assertThat(c.backslashEscapesSupported()).isTrue();
            assertThat(c.hashLineCommentSupported()).isTrue();
            assertThat(c.delimiterDirectiveSupported()).isTrue();
            assertThat(c.plsqlBlockDetector()).isSameAs(detector);
            assertThat(c.defaultStatementSeparator()).isEqualTo("//");
        }

        @Test
        @DisplayName("空分隔符 → IllegalArgumentException")
        void emptySeparatorRejected() {
            assertThatThrownBy(() -> SqlStatementBuilderConfig.builder().defaultStatementSeparator("").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("defaultStatementSeparator");
        }

        @Test
        @DisplayName("null 分隔符 → IllegalArgumentException")
        void nullSeparatorRejected() {
            assertThatThrownBy(() -> SqlStatementBuilderConfig.builder().defaultStatementSeparator(null).build())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("OraclePlsqlBlockDetector")
    class DetectorCases {

        private final OraclePlsqlBlockDetector detector = OraclePlsqlBlockDetector.INSTANCE;

        @ParameterizedTest(name = "[{index}] \"{0}\" → 进入 PL/SQL 块")
        @DisplayName("触发块检测的开头")
        @ValueSource(strings = {
                "CREATE PROCEDURE p AS BEGIN NULL; END;",
                "CREATE OR REPLACE PROCEDURE p AS BEGIN NULL; END;",
                "CREATE FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;",
                "CREATE OR REPLACE FUNCTION f RETURN NUMBER IS BEGIN NULL; END;",
                "CREATE PACKAGE pkg AS PROCEDURE x; END;",
                "CREATE OR REPLACE PACKAGE BODY pkg AS PROCEDURE x IS BEGIN NULL; END;",
                "CREATE PACKAGE BODY pkg AS PROCEDURE x IS BEGIN NULL; END;",
                "CREATE TRIGGER trg BEFORE INSERT ON t BEGIN NULL; END;",
                "CREATE OR REPLACE TRIGGER trg BEFORE INSERT ON t BEGIN NULL; END;",
                "CREATE TYPE t AS OBJECT (x NUMBER);",
                "DECLARE\n  v INT; BEGIN NULL; END;",
                "BEGIN\n  INSERT INTO t VALUES (1); END;"
        })
        void triggersBlock(String stmt) {
            assertThat(detector.startsPlsqlBlock(stmt.toUpperCase().trim())).isTrue();
            // 大小写不敏感（探测器内部已 upper？否——依赖调用方大写；补充小写不应误判，因为 lexer 总传大写）
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" → 不进入块")
        @DisplayName("不触发块检测的普通语句")
        @ValueSource(strings = {
                "SELECT 1 FROM dual",
                "CREATE TABLE t (id INT)",
                "CREATE INDEX idx ON t (id)",
                "CREATE VIEW v AS SELECT 1",
                "CREATE SCHEMA s",
                "CREATE USER u",
                "INSERT INTO t VALUES (1)"
        })
        void doesNotTrigger(String stmt) {
            assertThat(detector.startsPlsqlBlock(stmt.toUpperCase().trim())).isFalse();
        }

        @Test
        @DisplayName("null / 空输入 → false")
        void nullAndEmpty() {
            assertThat(detector.startsPlsqlBlock(null)).isFalse();
            assertThat(detector.startsPlsqlBlock("")).isFalse();
        }
    }
}
