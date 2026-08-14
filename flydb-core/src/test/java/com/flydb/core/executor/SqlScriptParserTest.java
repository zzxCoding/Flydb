package com.flydb.core.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SqlScriptParser} 与 {@link SqlScriptLexer} 的 null 守卫与结果契约测试。
 * （切分行为由 {@link SqlScriptLexerTest} 覆盖，本类只覆盖守卫与边界。）
 */
@DisplayName("SqlScriptParser / SqlScriptLexer 守卫")
class SqlScriptParserTest {

    @Test
    @DisplayName("Parser: null config → NullPointerException")
    void parserNullConfig() {
        assertThatThrownBy(() -> new SqlScriptParser(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config");
    }

    @Test
    @DisplayName("Parser: null script → NullPointerException")
    void parserNullScript() {
        SqlScriptParser parser = new SqlScriptParser(SqlStatementBuilderConfig.postgresql());
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sqlScript");
    }

    @Test
    @DisplayName("Lexer: null config → NullPointerException")
    void lexerNullConfig() {
        assertThatThrownBy(() -> new SqlScriptLexer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config");
    }

    @Test
    @DisplayName("Parser: 空脚本 → 空列表")
    void emptyScriptReturnsEmptyList() {
        List<SqlStatement> stmts =
                new SqlScriptParser(SqlStatementBuilderConfig.postgresql()).parse("");
        assertThat(stmts).isEmpty();
    }

    @Test
    @DisplayName("Parser: 返回不可变列表")
    void resultUnmodifiable() {
        List<SqlStatement> stmts =
                new SqlScriptParser(SqlStatementBuilderConfig.postgresql()).parse("SELECT 1;");
        assertThat(stmts).isUnmodifiable();
    }
}
