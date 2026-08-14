package com.flydb.core.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 脚本字符级切分状态机（包级可见，由 {@link SqlScriptParser} 调用）。
 *
 * <p>单遍扫描输入字符串，按 {@link SqlStatementBuilderConfig} 表达的家族差异产出
 * {@link SqlStatement} 列表。状态机定义见设计 04 §1.2，关键规则见 §1.3：
 * <ul>
 *   <li>DEFAULT 状态遇到当前分隔符 → 产出一条语句（Oracle 系先做 PL/SQL 块检测）</li>
 *   <li>MySQL 系 {@code DELIMITER xxx} 指令：切换分隔符，不产出语句</li>
 *   <li>PL/SQL 块（Oracle 系）：进入后 {@code ;} 为块内分隔，唯一终止符是独占一行的 {@code /}</li>
 *   <li>dollar-quoting（PG 系）：{@code $tag$ ... $tag$}，块内一切字符原样累积</li>
 *   <li>纯注释/空白切出的空语句丢弃；EOF 时缓冲非空白 → 产出最后一条</li>
 * </ul>
 *
 * <p>每个 {@link LexerState} 一个私有转移方法，以满足"函数 &lt;50 行、嵌套 ≤4"约束。
 * 家族差异只通过 {@code config} 读取决定行为，没有任何 if(family) 分支。
 */
final class SqlScriptLexer {

    private enum LexerState {
        DEFAULT,
        IN_LINE_COMMENT,
        IN_BLOCK_COMMENT,
        IN_SINGLE_QUOTED_STRING,
        IN_QUOTED_IDENTIFIER,
        IN_DOLLAR_QUOTED_BLOCK,
        IN_PLSQL_BLOCK
    }

    /** MySQL {@code DELIMITER xxx} 指令整行匹配（大小写不敏感）。 */
    private static final Pattern DELIMITER_DIRECTIVE = Pattern.compile("(?i)DELIMITER\\s+(\\S+)");

    private final SqlStatementBuilderConfig config;
    private final List<SqlStatement> result = new ArrayList<>();

    private String source;
    private int len;
    private int pos;
    private int line = 1;

    private final StringBuilder buffer = new StringBuilder();
    /** buffer 中当前行起始索引（用于 DELIMITER 指令与 PL/SQL {@code /} 终止符的整行判定）。 */
    private int bufLineStart = 0;

    private LexerState state = LexerState.DEFAULT;
    /** 进入注释/字符串/标识符子状态前的来源状态（DEFAULT 或 IN_PLSQL_BLOCK），子状态结束后回到此状态。 */
    private LexerState returnState = LexerState.DEFAULT;

    private boolean hasCode = false;
    private int codeStartLine = 0;

    private String delimiter;
    private String dollarTag;

    SqlScriptLexer(SqlStatementBuilderConfig config) {
        if (config == null) {
            throw new NullPointerException("config must not be null");
        }
        this.config = config;
    }

    List<SqlStatement> tokenize(String source) {
        this.source = source;
        this.len = source.length();
        this.delimiter = config.defaultStatementSeparator();
        while (pos < len) {
            switch (state) {
                case DEFAULT:
                    handleDefault();
                    break;
                case IN_LINE_COMMENT:
                    handleLineComment();
                    break;
                case IN_BLOCK_COMMENT:
                    handleBlockComment();
                    break;
                case IN_SINGLE_QUOTED_STRING:
                    handleSingleQuotedString();
                    break;
                case IN_QUOTED_IDENTIFIER:
                    handleQuotedIdentifier();
                    break;
                case IN_DOLLAR_QUOTED_BLOCK:
                    handleDollarQuotedBlock();
                    break;
                case IN_PLSQL_BLOCK:
                    handlePlsqlBlock();
                    break;
                default:
                    throw new IllegalStateException("Unexpected lexer state: " + state);
            }
        }
        handleEof();
        return result;
    }

    // ------------------------------------------------------------------
    //  DEFAULT：判分隔符终止 / PL/SQL 块检测 / 进入子状态
    // ------------------------------------------------------------------
    private void handleDefault() {
        char c = source.charAt(pos);
        if (c == '\r' || c == '\n') {
            handleLineBreak(c);
            return;
        }
        if (matchesDelimiterAt(pos)) {
            handleDelimiterInDefault();
            return;
        }
        scanBody(c, LexerState.DEFAULT);
    }

    /** 分隔符命中：先查 PL/SQL 块检测（Oracle 系），命中则进块；否则正常产出语句。 */
    private void handleDelimiterInDefault() {
        if (config.plsqlBlockDetector() != null) {
            String detection = stripComments(buffer.toString()).trim().toUpperCase();
            if (!detection.isEmpty() && config.plsqlBlockDetector().startsPlsqlBlock(detection)) {
                // 分隔符成为块内字符，整体累积，状态切换
                appendRaw(delimiter);
                pos += delimiter.length();
                state = LexerState.IN_PLSQL_BLOCK;
                return;
            }
        }
        pos += delimiter.length();
        flushStatement();
    }

    // ------------------------------------------------------------------
    //  子状态共享体：注释/字符串/标识符/dollar-quoting 识别（不产出语句）
    //  由 DEFAULT 与 IN_PLSQL_BLOCK 复用，returnTo 决定子状态结束后的归宿
    // ------------------------------------------------------------------
    private void scanBody(char c, LexerState returnTo) {
        if (c == '-' && peek(pos + 1) == '-') {
            enterSubState(LexerState.IN_LINE_COMMENT, returnTo, c);
            return;
        }
        if (c == '#' && config.hashLineCommentSupported()) {
            enterSubState(LexerState.IN_LINE_COMMENT, returnTo, c);
            return;
        }
        if (c == '/' && peek(pos + 1) == '*') {
            enterSubState(LexerState.IN_BLOCK_COMMENT, returnTo, c);
            return;
        }
        if (c == '\'') {
            markCode();
            enterSubState(LexerState.IN_SINGLE_QUOTED_STRING, returnTo, c);
            return;
        }
        if (c == config.identifierQuoteChar()) {
            markCode();
            enterSubState(LexerState.IN_QUOTED_IDENTIFIER, returnTo, c);
            return;
        }
        if (config.dollarQuotingSupported() && c == '$') {
            String tag = readDollarTag();
            if (tag != null) {
                markCode();
                dollarTag = tag;
                this.returnState = returnTo;
                state = LexerState.IN_DOLLAR_QUOTED_BLOCK;
                appendRaw(tag);
                pos += tag.length();
                return;
            }
        }
        // 空白不触发 hasCode（纯空白/注释切出的空语句需丢弃），但仍写入缓冲
        if (!Character.isWhitespace(c)) {
            markCode();
        }
        appendRaw(c);
        pos++;
    }

    private void enterSubState(LexerState sub, LexerState returnTo, char opener) {
        this.returnState = returnTo;
        state = sub;
        appendRaw(opener);
        pos++;
    }

    // ------------------------------------------------------------------
    //  注释 / 字符串 / 标识符 / dollar-quoting 各状态转移
    // ------------------------------------------------------------------
    private void handleLineComment() {
        char c = source.charAt(pos);
        if (c == '\r' || c == '\n') {
            state = returnState;   // 交还给来源状态处理换行
            return;
        }
        appendRaw(c);
        pos++;
    }

    private void handleBlockComment() {
        char c = source.charAt(pos);
        if (c == '*' && peek(pos + 1) == '/') {
            appendRaw('*');
            appendRaw('/');
            pos += 2;
            state = returnState;
            return;
        }
        if (appendNormalizedNewline(c)) {
            return;
        }
        appendRaw(c);
        pos++;
    }

    private void handleSingleQuotedString() {
        char c = source.charAt(pos);
        if (config.backslashEscapesSupported() && c == '\\') {
            appendRaw('\\');
            if (pos + 1 < len) {
                appendRaw(source.charAt(pos + 1));
                pos += 2;
            } else {
                pos++;
            }
            return;
        }
        if (c == '\'' && peek(pos + 1) == '\'') {
            appendRaw('\'');
            appendRaw('\'');
            pos += 2;
            return;
        }
        if (c == '\'') {
            appendRaw('\'');
            pos++;
            state = returnState;
            return;
        }
        if (appendNormalizedNewline(c)) {
            return;
        }
        appendRaw(c);
        pos++;
    }

    private void handleQuotedIdentifier() {
        char c = source.charAt(pos);
        char q = config.identifierQuoteChar();
        if (c == q && peek(pos + 1) == q) {
            appendRaw(c);
            appendRaw(q);
            pos += 2;
            return;
        }
        if (c == q) {
            appendRaw(c);
            pos++;
            state = returnState;
            return;
        }
        if (appendNormalizedNewline(c)) {
            return;
        }
        appendRaw(c);
        pos++;
    }

    private void handleDollarQuotedBlock() {
        if (source.regionMatches(pos, dollarTag, 0, dollarTag.length())) {
            appendRaw(dollarTag);
            pos += dollarTag.length();
            state = returnState;
            return;
        }
        char c = source.charAt(pos);
        if (appendNormalizedNewline(c)) {
            return;
        }
        appendRaw(c);
        pos++;
    }

    // ------------------------------------------------------------------
    //  IN_PLSQL_BLOCK：; 非终止，唯一终止符是独占一行的 /（在 handleLineBreak 判定）
    // ------------------------------------------------------------------
    private void handlePlsqlBlock() {
        char c = source.charAt(pos);
        if (c == '\r' || c == '\n') {
            handleLineBreak(c);
            return;
        }
        // 块内仍需尊重注释/字符串（避免注释里出现 / 行误判终止），但不识别分隔符
        scanBody(c, LexerState.IN_PLSQL_BLOCK);
    }

    // ------------------------------------------------------------------
    //  换行处理：DELIMITER 指令 / PL/SQL / 终止 / 行号与缓冲行起点维护
    // ------------------------------------------------------------------
    private void handleLineBreak(char c) {
        String completedLine = buffer.substring(bufLineStart);
        boolean crlf = (c == '\r' && peek(pos + 1) == '\n');

        if (state == LexerState.DEFAULT && config.delimiterDirectiveSupported()) {
            Matcher m = DELIMITER_DIRECTIVE.matcher(completedLine.trim());
            if (m.matches()) {
                delimiter = m.group(1);
                buffer.delete(bufLineStart, buffer.length());
                bufLineStart = buffer.length();
                // DELIMITER 指令本身不是代码：删除该行后若缓冲仅余空白，清除代码标记
                if (buffer.toString().trim().isEmpty()) {
                    hasCode = false;
                    codeStartLine = 0;
                }
                advancePastNewline(crlf);
                return;
            }
        }
        if (state == LexerState.IN_PLSQL_BLOCK && completedLine.trim().equals("/")) {
            emitPlsqlBlock();
            advancePastNewline(crlf);
            return;
        }
        buffer.append('\n');
        bufLineStart = buffer.length();
        advancePastNewline(crlf);
    }

    private void advancePastNewline(boolean crlf) {
        pos += crlf ? 2 : 1;
        line++;
    }

    /** 输出 DEFAULT 缓冲中的语句（hasCode 为真时）。 */
    private void flushStatement() {
        if (hasCode) {
            result.add(new SqlStatement(buffer.toString().trim(), codeStartLine));
        }
        resetBuffer();
    }

    /** 输出 PL/SQL 块（buffer 中 / 行之前的内容），回到 DEFAULT。 */
    private void emitPlsqlBlock() {
        if (hasCode) {
            result.add(new SqlStatement(buffer.substring(0, bufLineStart).trim(), codeStartLine));
        }
        resetBuffer();
        state = LexerState.DEFAULT;
    }

    private void resetBuffer() {
        buffer.setLength(0);
        bufLineStart = 0;
        hasCode = false;
        codeStartLine = 0;
    }

    // ------------------------------------------------------------------
    //  EOF：残余行（无尾换行）的 DELIMITER / PL/SQL / 最后一条语句处理
    // ------------------------------------------------------------------
    private void handleEof() {
        String lastLine = buffer.substring(bufLineStart);
        String trimmedLast = lastLine.trim();

        Matcher dirMatcher = DELIMITER_DIRECTIVE.matcher(trimmedLast);
        if (state == LexerState.DEFAULT && config.delimiterDirectiveSupported() && dirMatcher.matches()) {
            delimiter = dirMatcher.group(1);
            buffer.delete(bufLineStart, buffer.length());
            return;
        }
        if (state == LexerState.IN_PLSQL_BLOCK && trimmedLast.equals("/")) {
            emitPlsqlBlock();
            return;
        }
        if (hasCode) {
            result.add(new SqlStatement(buffer.toString().trim(), codeStartLine));
        }
    }

    // ------------------------------------------------------------------
    //  小工具
    // ------------------------------------------------------------------
    private char peek(int offset) {
        return (offset >= 0 && offset < len) ? source.charAt(offset) : '\0';
    }

    private boolean matchesDelimiterAt(int offset) {
        return source.regionMatches(offset, delimiter, 0, delimiter.length());
    }

    private void appendRaw(char c) {
        buffer.append(c);
    }

    private void appendRaw(String s) {
        buffer.append(s);
    }

    /** 首次遇到代码字符时记录起始行号（注释/空白不触发）。 */
    private void markCode() {
        if (!hasCode) {
            hasCode = true;
            codeStartLine = line;
        }
    }

    /**
     * 若当前字符是换行（含 CRLF / 单 CR），归一化为 LF 写入缓冲并推进；返回 true 表示已消费。
     */
    private boolean appendNormalizedNewline(char c) {
        if (c != '\r' && c != '\n') {
            return false;
        }
        boolean crlf = (c == '\r' && peek(pos + 1) == '\n');
        buffer.append('\n');
        bufLineStart = buffer.length();
        pos += crlf ? 2 : 1;
        line++;
        return true;
    }

    /**
     * 在 DEFAULT 遇到候选分隔符时，从当前位置读取 dollar-quote 开标签 {@code $tag$}；
     * 非法（无配对 {@code $}、tag 含非标识符字符、{@code $1} 位置参数等）返回 null。
     */
    private String readDollarTag() {
        int end = pos + 1;
        while (end < len) {
            char c = source.charAt(end);
            if (c == '$') {
                return source.substring(pos, end + 1);
            }
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return null;
            }
            end++;
        }
        return null;
    }

    /** 为 PL/SQL 块检测剥离 -- 行注释与 /* 块注释（仅用于检测输入，非解析）。 */
    private static String stripComments(String s) {
        String noBlock = s.replaceAll("/\\*.*?\\*/", " ");
        return noBlock.replaceAll("--[^\\n]*", " ");
    }
}
