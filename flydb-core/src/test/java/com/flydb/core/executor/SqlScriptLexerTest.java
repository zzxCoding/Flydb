package com.flydb.core.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SQL 脚本切分状态机测试（设计 04 §1、08 §1）。
 *
 * <p>覆盖三家族 fixture 全绿 + 跨家族对照（同输入不同配置结果不同，证明"差异表达为配置数据，
 * 不以代码分支表达"）+ 边界（空脚本、纯注释、EOF 无分号、CRLF）。
 *
 * <p>每个 fixture 同时断言语句数量与起始行号；行号是错误定位的关键。
 */
@DisplayName("SqlScriptLexer / SqlScriptParser")
class SqlScriptLexerTest {

    private static String fixture(String classpathPath) {
        try (InputStream in = SqlScriptLexerTest.class.getResourceAsStream(classpathPath)) {
            assertThat(in).as("fixture 存在: %s", classpathPath).isNotNull();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[1024];
                int n;
                while ((n = r.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SqlStatement> parse(SqlStatementBuilderConfig config, String classpathPath) {
        return new SqlScriptParser(config).parse(fixture(classpathPath));
    }

    private static List<Integer> lineNumbers(List<SqlStatement> stmts) {
        return stmts.stream().map(SqlStatement::lineNumber).collect(Collectors.toList());
    }

    // ---------------------------------------------------------------------
    // PostgreSQL 家族：dollar-quoting、注释、'' 转义、CRLF、Unicode、无尾分号
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("PostgreSQL 家族")
    class PostgreSqlFamily {

        private final SqlStatementBuilderConfig config = SqlStatementBuilderConfig.postgresql();

        @Test
        @DisplayName("两条简单语句：数量与行号")
        void simple() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/simple.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 2);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT 1");
            assertThat(stmts.get(1).sql()).isEqualTo("SELECT 2");
        }

        @Test
        @DisplayName("-- 与 /* */ 注释：纯注释行不计入起始行号，hint 原样保留")
        void comments() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/comments.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(4, 5);
            assertThat(stmts.get(0).sql()).contains("SELECT /*+ INDEX(t) */ 1");
            assertThat(stmts.get(1).sql()).contains("INSERT INTO t VALUES (2)");
        }

        @Test
        @DisplayName("$$ dollar-quoting：块内分号不切分")
        void dollarQuotingEmptyTag() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/dollar_quoting.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 6);
            String fn = stmts.get(0).sql();
            assertThat(fn).contains("CREATE FUNCTION add");
            assertThat(fn).contains("RETURN a + b;");   // 块内分号保留
            assertThat(fn).contains("LANGUAGE plpgsql");
            assertThat(stmts.get(1).sql()).isEqualTo("SELECT add(1, 2)");
        }

        @Test
        @DisplayName("$tag$ dollar-quoting：块内含 $$ 与分号均原样保留")
        void dollarQuotingTagged() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/dollar_quoting_tagged.sql");
            assertThat(stmts).hasSize(1);
            assertThat(lineNumbers(stmts)).containsExactly(1);
            String fn = stmts.get(0).sql();
            assertThat(fn).contains("CREATE FUNCTION f");
            assertThat(fn).contains("has $$ and ; inside");   // 块内 $$ 与 ; 不切分
            assertThat(fn).contains("$body$ LANGUAGE plpgsql");
        }

        @Test
        @DisplayName("'' 转义与跨行字符串")
        void singleQuoteEscape() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/string_escape.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 2);
            assertThat(stmts.get(0).sql()).contains("it''s ok");
            assertThat(stmts.get(1).sql()).contains("line");
            assertThat(stmts.get(1).sql()).contains("break");
        }

        @Test
        @DisplayName("最后一条语句无分号：EOF 产出")
        void noTrailingSemicolon() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/no_trailing_semicolon.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 2);
            assertThat(stmts.get(0).sql()).isEqualTo("CREATE TABLE t (id INT)");
            assertThat(stmts.get(1).sql()).isEqualTo("INSERT INTO t VALUES (1)");
        }

        @Test
        @DisplayName("CRLF 文件：行号按归一化行计")
        void crlf() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/crlf.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 2);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT 1");
            assertThat(stmts.get(1).sql()).isEqualTo("SELECT 2");
        }

        @Test
        @DisplayName("Unicode 内容：中文注释/字符串正确处理")
        void unicode() {
            List<SqlStatement> stmts = parse(config, "/parser/postgresql/unicode.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(2, 3);
            assertThat(stmts.get(0).sql()).contains("你好世界");
            assertThat(stmts.get(1).sql()).contains("日本語テスト");
        }
    }

    // ---------------------------------------------------------------------
    // MySQL 家族：# 注释、DELIMITER 指令、\' 转义、反引号标识符
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("MySQL 家族")
    class MySqlFamily {

        private final SqlStatementBuilderConfig config = SqlStatementBuilderConfig.mysql();

        @Test
        @DisplayName("# 行注释（仅 MySQL 系启用）：前导 # 注释不触发 hasCode，尾随 # 在分号后丢弃")
        void hashComment() {
            List<SqlStatement> stmts = parse(config, "/parser/mysql/hash_comment.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(2, 3);
            // 前导 # 注释文本保留在同一条语句缓冲里，但 codeStartLine 指向 SELECT 行（注释不计入代码起点）
            assertThat(stmts.get(0).sql()).contains("SELECT 1");
            assertThat(stmts.get(0).lineNumber()).isEqualTo(2);
            // 尾随 # trailing comment 在分号之后 → 归入下一条缓冲，EOF 丢弃
            assertThat(stmts.get(1).sql()).isEqualTo("SELECT 2");
        }

        @Test
        @DisplayName("DELIMITER // 指令：存储过程内分号不切分，指令行不产出语句")
        void delimiterDirective() {
            List<SqlStatement> stmts = parse(config, "/parser/mysql/delimiter_procedure.sql");
            assertThat(stmts).hasSize(3);
            assertThat(lineNumbers(stmts)).containsExactly(1, 3, 9);
            assertThat(stmts.get(0).sql()).isEqualTo("DROP PROCEDURE IF EXISTS p");
            String proc = stmts.get(1).sql();
            assertThat(proc).contains("CREATE PROCEDURE p");
            assertThat(proc).contains("SELECT 1;");
            assertThat(proc).contains("SELECT 2;");
            assertThat(proc).contains("END");
            assertThat(proc).doesNotContain("//");   // 分隔符本身不计入语句
            assertThat(stmts.get(2).sql()).isEqualTo("SELECT 3");
        }

        @Test
        @DisplayName("\\' 反斜杠转义：字符串不提前结束")
        void backslashEscape() {
            List<SqlStatement> stmts = parse(config, "/parser/mysql/backslash_escape.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 2);
            assertThat(stmts.get(0).sql()).contains("it\\'s ok");
            assertThat(stmts.get(1).sql()).contains("tab\\there");
        }

        @Test
        @DisplayName("反引号标识符：块内字符不误判")
        void backtickIdentifier() {
            List<SqlStatement> stmts = parse(config, "/parser/mysql/backtick_identifier.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 2);
            assertThat(stmts.get(0).sql()).contains("`order`");
            assertThat(stmts.get(0).sql()).contains("`select`");
        }
    }

    // ---------------------------------------------------------------------
    // Oracle 家族：PL/SQL 块（过程/函数/触发器/裸 DECLARE/裸 BEGIN），/ 终止
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("Oracle 家族（PL/SQL 块）")
    class OracleFamily {

        private final SqlStatementBuilderConfig config = SqlStatementBuilderConfig.oracle();

        @Test
        @DisplayName("CREATE PROCEDURE ... END; /：块内分号不切分，/ 行不进语句文本")
        void plsqlProcedure() {
            List<SqlStatement> stmts = parse(config, "/parser/oracle/plsql_procedure.sql");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 6);
            String block = stmts.get(0).sql();
            assertThat(block).contains("CREATE OR REPLACE PROCEDURE greet");
            assertThat(block).contains("DBMS_OUTPUT.PUT_LINE('hello');");
            assertThat(block).endsWith("END;");      // / 终止符不计入
            assertThat(block).doesNotContain("\n/\n");
            assertThat(stmts.get(1).sql()).isEqualTo("SELECT 1 FROM dual");
        }

        @Test
        @DisplayName("CREATE FUNCTION ... IS v_result NUMBER;：首个分号触发块检测")
        void plsqlFunction() {
            List<SqlStatement> stmts = parse(config, "/parser/oracle/plsql_function.sql");
            assertThat(stmts).hasSize(1);
            assertThat(lineNumbers(stmts)).containsExactly(1);
            String block = stmts.get(0).sql();
            assertThat(block).contains("CREATE OR REPLACE FUNCTION add_one");
            assertThat(block).contains("v_result NUMBER;");   // 首分号所在行保留
            assertThat(block).endsWith("END;");
        }

        @Test
        @DisplayName("CREATE TRIGGER ... END; /")
        void plsqlTrigger() {
            List<SqlStatement> stmts = parse(config, "/parser/oracle/plsql_trigger.sql");
            assertThat(stmts).hasSize(1);
            assertThat(lineNumbers(stmts)).containsExactly(1);
            String block = stmts.get(0).sql();
            assertThat(block).contains("CREATE OR REPLACE TRIGGER trg_before_insert");
            assertThat(block).contains(":NEW.id := seq.nextval;");
            assertThat(block).endsWith("END;");
        }

        @Test
        @DisplayName("裸 DECLARE 块：BEGIN/END 内分号不切分")
        void plsqlAnonymousDeclare() {
            List<SqlStatement> stmts = parse(config, "/parser/oracle/plsql_anonymous_declare.sql");
            assertThat(stmts).hasSize(1);
            assertThat(lineNumbers(stmts)).containsExactly(1);
            String block = stmts.get(0).sql();
            assertThat(block).contains("DECLARE");
            assertThat(block).contains("v_count := v_count + 1;");
            assertThat(block).endsWith("END;");
        }

        @Test
        @DisplayName("裸 BEGIN 块：内部多条 INSERT 分号不切分")
        void plsqlAnonymousBegin() {
            List<SqlStatement> stmts = parse(config, "/parser/oracle/plsql_anonymous_begin.sql");
            assertThat(stmts).hasSize(1);
            assertThat(lineNumbers(stmts)).containsExactly(1);
            String block = stmts.get(0).sql();
            assertThat(block).contains("INSERT INTO t VALUES (1);");
            assertThat(block).contains("INSERT INTO t VALUES (2);");
            assertThat(block).endsWith("END;");
        }
    }

    // ---------------------------------------------------------------------
    // 跨家族对照：同输入、不同配置 → 不同结果（差异表达为配置数据，设计 04 §1.2）
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("跨家族对照（差异 = 配置数据，非代码分支）")
    class CrossFamily {

        @Test
        @DisplayName("# 注释：MySQL 视为注释（不触发 hasCode → 纯注释被丢弃），PG 视为普通代码")
        void hashCommentDiffersByConfig() {
            String script = "# only a comment;\n";
            List<SqlStatement> mysql = new SqlScriptParser(SqlStatementBuilderConfig.mysql()).parse(script);
            List<SqlStatement> pg = new SqlScriptParser(SqlStatementBuilderConfig.postgresql()).parse(script);

            // MySQL：# 是注释，hasCode 始终 false → 纯注释语句被丢弃
            assertThat(mysql).isEmpty();
            // PG：# 是普通字符，触发 hasCode → 产出一条语句
            assertThat(pg).hasSize(1);
            assertThat(pg.get(0).sql()).contains("# only a comment");
        }

        @Test
        @DisplayName("dollar-quoting：PG 视为块不切分，MySQL 视为普通字符被分号切分")
        void dollarQuotingDiffersByConfig() {
            String script = "CREATE FUNCTION f() RETURNS INT AS $$\nBEGIN RETURN 1; END;\n$$ LANGUAGE plpgsql;";
            List<SqlStatement> pg = new SqlScriptParser(SqlStatementBuilderConfig.postgresql()).parse(script);
            List<SqlStatement> mysql = new SqlScriptParser(SqlStatementBuilderConfig.mysql()).parse(script);

            assertThat(pg).hasSize(1);              // 整个函数是一条语句
            assertThat(mysql.size()).isGreaterThan(1);  // 块内分号被切分
        }

        @Test
        @DisplayName("PL/SQL 块：Oracle 识别为单块，PG 不识别被分号切分")
        void plsqlBlockDiffersByConfig() {
            List<SqlStatement> oracle = parse(SqlStatementBuilderConfig.oracle(),
                    "/parser/oracle/plsql_procedure.sql");
            List<SqlStatement> pg = parse(SqlStatementBuilderConfig.postgresql(),
                    "/parser/oracle/plsql_procedure.sql");

            assertThat(oracle).hasSize(2);   // 块 + SELECT
            assertThat(pg.size()).isGreaterThan(2);  // 块内分号被切分，/ 视为普通字符
        }
    }

    // ---------------------------------------------------------------------
    // 边界：空脚本、纯注释、连续分号、EOF 无分号、DELIMITER 在 EOF
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        private final SqlStatementBuilderConfig pg = SqlStatementBuilderConfig.postgresql();

        @Test
        @DisplayName("空脚本 → 0 条语句")
        void empty() {
            assertThat(new SqlScriptParser(pg).parse("")).isEmpty();
        }

        @Test
        @DisplayName("纯空白 → 0 条语句")
        void whitespaceOnly() {
            assertThat(new SqlScriptParser(pg).parse("   \n\t\n  ")).isEmpty();
        }

        @Test
        @DisplayName("纯注释（无分号）→ 0 条语句")
        void pureCommentNoSemicolon() {
            assertThat(new SqlScriptParser(pg).parse("-- just a comment\n/* block */\n")).isEmpty();
        }

        @Test
        @DisplayName("纯注释以分号结尾 → 0 条语句（空语句丢弃）")
        void pureCommentWithSemicolon() {
            assertThat(new SqlScriptParser(pg).parse("-- comment\n;\n")).isEmpty();
        }

        @Test
        @DisplayName("连续分号 → 仅产出非空语句")
        void consecutiveSeparators() {
            List<SqlStatement> stmts = new SqlScriptParser(pg).parse(";;;SELECT 1;;;SELECT 2;;;");
            assertThat(stmts).hasSize(2);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT 1");
            assertThat(stmts.get(1).sql()).isEqualTo("SELECT 2");
        }

        @Test
        @DisplayName("单条语句无尾分号 → EOF 产出")
        void singleNoSemicolon() {
            List<SqlStatement> stmts = new SqlScriptParser(pg).parse("SELECT 1");
            assertThat(stmts).hasSize(1);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT 1");
            assertThat(stmts.get(0).lineNumber()).isEqualTo(1);
        }

        @Test
        @DisplayName("MySQL DELIMITER 在 EOF（无尾换行）：切换仍生效")
        void delimiterAtEof() {
            // DELIMITER // 后无换行直接 EOF；切换分隔符后无后续语句 → 0 条
            String script = "DELIMITER //";
            List<SqlStatement> stmts =
                    new SqlScriptParser(SqlStatementBuilderConfig.mysql()).parse(script);
            assertThat(stmts).isEmpty();
        }

        @Test
        @DisplayName("起始行号穿过前导空行后正确定位")
        void lineNumberAfterBlankLines() {
            List<SqlStatement> stmts = new SqlScriptParser(pg).parse("\n\n   SELECT 1;");
            assertThat(stmts).hasSize(1);
            assertThat(stmts.get(0).lineNumber()).isEqualTo(3);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("解析结果列表不可变")
        void resultIsUnmodifiable() {
            List<SqlStatement> stmts = new SqlScriptParser(pg).parse("SELECT 1;");
            assertThat(stmts).isUnmodifiable();
        }
    }

    // ---------------------------------------------------------------------
    // 健壮性边界：lone CR、非法 dollar tag、EOF 反斜杠、未闭合注释/字符串
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("健壮性边界")
    class RobustnessEdges {

        private final SqlStatementBuilderConfig pg = SqlStatementBuilderConfig.postgresql();

        @Test
        @DisplayName("lone \\r（旧 Mac 换行）按一行计")
        void loneCr() {
            List<SqlStatement> stmts = new SqlScriptParser(SqlStatementBuilderConfig.postgresql())
                    .parse("SELECT 1;\rSELECT 2;");
            assertThat(stmts).hasSize(2);
            assertThat(lineNumbers(stmts)).containsExactly(1, 2);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT 1");
            assertThat(stmts.get(1).sql()).isEqualTo("SELECT 2");
        }

        @Test
        @DisplayName("$abc（无配对 $）→ 普通字符，非 dollar-quote")
        void dollarTagInvalidNoClosing() {
            List<SqlStatement> stmts = new SqlScriptParser(SqlStatementBuilderConfig.postgresql())
                    .parse("SELECT $abc;");
            assertThat(stmts).hasSize(1);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT $abc");
        }

        @Test
        @DisplayName("$1（PG 位置参数）→ 普通字符，非 dollar-quote")
        void dollarPositionalParam() {
            List<SqlStatement> stmts = new SqlScriptParser(SqlStatementBuilderConfig.postgresql())
                    .parse("SELECT $1;");
            assertThat(stmts).hasSize(1);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT $1");
        }

        @Test
        @DisplayName("MySQL 字符串末尾孤立反斜杠（EOF）→ 原样保留")
        void backslashAtEof() {
            List<SqlStatement> stmts = new SqlScriptParser(SqlStatementBuilderConfig.mysql())
                    .parse("SELECT 'abc\\");
            assertThat(stmts).hasSize(1);
            assertThat(stmts.get(0).sql()).endsWith("\\");
        }

        @Test
        @DisplayName("未闭合块注释到 EOF → 注释不计入语句（hasCode 未触发）")
        void unclosedBlockCommentAtEof() {
            List<SqlStatement> stmts = new SqlScriptParser(pg).parse("SELECT 1; /* unfinished");
            assertThat(stmts).hasSize(1);
            assertThat(stmts.get(0).sql()).isEqualTo("SELECT 1");
        }

        @Test
        @DisplayName("未闭合字符串到 EOF → 缓冲非空，EOF 产出（容错）")
        void unclosedStringAtEof() {
            List<SqlStatement> stmts = new SqlScriptParser(pg).parse("SELECT 'unfinished");
            assertThat(stmts).hasSize(1);
            assertThat(stmts.get(0).sql()).contains("unfinished");
        }
    }
}
