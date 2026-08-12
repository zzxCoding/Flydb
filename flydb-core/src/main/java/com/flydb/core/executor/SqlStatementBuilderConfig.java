package com.flydb.core.executor;

/**
 * SQL 语句切分器的家族配置（不可变）。
 *
 * <p>家族差异以配置数据表达，不以代码分支表达——{@link SqlScriptLexer} 只有一份实现，读取本配置
 * 决定行为（设计 04 §1.2，约束："不要把方言差异写成 Lexer 里的 if/else 家族分支"）。
 *
 * <p>三家族预设：
 * <ul>
 *   <li>{@link #postgresql()}：{@code "} 标识符引号、dollar-quoting、无反斜杠转义、无 {@code #} 注释、
 *       无 DELIMITER 指令、无 PL/SQL 块</li>
 *   <li>{@link #mysql()}：{@code `} 标识符引号、反斜杠转义、{@code #} 行注释、DELIMITER 指令、
 *       无 dollar-quoting、无 PL/SQL 块</li>
 *   <li>{@link #oracle()}：{@code "} 标识符引号、PL/SQL 块（{@link OraclePlsqlBlockDetector}）、
 *       其余与 PostgreSQL 一致（无反斜杠/{@code #}/DELIMITER/dollar-quoting）</li>
 * </ul>
 */
public final class SqlStatementBuilderConfig {

    private static final SqlStatementBuilderConfig POSTGRESQL = new SqlStatementBuilderConfig(
            '"', true, false, false, false, null, ";");
    private static final SqlStatementBuilderConfig MYSQL = new SqlStatementBuilderConfig(
            '`', false, true, true, true, null, ";");
    private static final SqlStatementBuilderConfig ORACLE = new SqlStatementBuilderConfig(
            '"', false, false, false, false, OraclePlsqlBlockDetector.INSTANCE, ";");

    private final char identifierQuoteChar;
    private final boolean dollarQuotingSupported;
    private final boolean backslashEscapesSupported;
    private final boolean hashLineCommentSupported;
    private final boolean delimiterDirectiveSupported;
    private final PlsqlBlockDetector plsqlBlockDetector;
    private final String defaultStatementSeparator;

    private SqlStatementBuilderConfig(char identifierQuoteChar,
                                      boolean dollarQuotingSupported,
                                      boolean backslashEscapesSupported,
                                      boolean hashLineCommentSupported,
                                      boolean delimiterDirectiveSupported,
                                      PlsqlBlockDetector plsqlBlockDetector,
                                      String defaultStatementSeparator) {
        if (defaultStatementSeparator == null || defaultStatementSeparator.isEmpty()) {
            throw new IllegalArgumentException("defaultStatementSeparator must be non-empty");
        }
        this.identifierQuoteChar = identifierQuoteChar;
        this.dollarQuotingSupported = dollarQuotingSupported;
        this.backslashEscapesSupported = backslashEscapesSupported;
        this.hashLineCommentSupported = hashLineCommentSupported;
        this.delimiterDirectiveSupported = delimiterDirectiveSupported;
        this.plsqlBlockDetector = plsqlBlockDetector;
        this.defaultStatementSeparator = defaultStatementSeparator;
    }

    /** PostgreSQL 家族预设（不可变单例）。 */
    public static SqlStatementBuilderConfig postgresql() {
        return POSTGRESQL;
    }

    /** MySQL 家族预设（不可变单例）。 */
    public static SqlStatementBuilderConfig mysql() {
        return MYSQL;
    }

    /** Oracle 家族预设（不可变单例）。 */
    public static SqlStatementBuilderConfig oracle() {
        return ORACLE;
    }

    /**
     * 自定义配置构造器（供外部方言/测试使用；MVP 三家族用上面的预设即可）。
     */
    public static Builder builder() {
        return new Builder();
    }

    /** 标识符引号字符：{@code "}（PG/Oracle 系）或 {@code `}（MySQL 系）。 */
    public char identifierQuoteChar() {
        return identifierQuoteChar;
    }

    /** 是否支持 dollar-quoting（{@code $tag$ ... $tag$}），仅 PG 系。 */
    public boolean dollarQuotingSupported() {
        return dollarQuotingSupported;
    }

    /** 是否支持反斜杠转义（{@code \'}/{@code \\}），仅 MySQL 系。 */
    public boolean backslashEscapesSupported() {
        return backslashEscapesSupported;
    }

    /** 是否支持 {@code #} 行注释，仅 MySQL 系。 */
    public boolean hashLineCommentSupported() {
        return hashLineCommentSupported;
    }

    /** 是否支持 {@code DELIMITER xxx} 指令，仅 MySQL 系。 */
    public boolean delimiterDirectiveSupported() {
        return delimiterDirectiveSupported;
    }

    /** PL/SQL 块探测器，仅 Oracle 系非 null；为 null 表示该家族不支持 PL/SQL 块。 */
    public PlsqlBlockDetector plsqlBlockDetector() {
        return plsqlBlockDetector;
    }

    /** 默认语句分隔符（恒为 {@code ";"}）。 */
    public String defaultStatementSeparator() {
        return defaultStatementSeparator;
    }

    /**
     * 自定义配置构造器。未设置的布尔项默认 false；分隔符默认 {@code ";"}；
     * 标识符引号默认 {@code "}；探测器默认 null。
     */
    public static final class Builder {
        private char identifierQuoteChar = '"';
        private boolean dollarQuotingSupported = false;
        private boolean backslashEscapesSupported = false;
        private boolean hashLineCommentSupported = false;
        private boolean delimiterDirectiveSupported = false;
        private PlsqlBlockDetector plsqlBlockDetector = null;
        private String defaultStatementSeparator = ";";

        private Builder() {
        }

        public Builder identifierQuoteChar(char c) {
            this.identifierQuoteChar = c;
            return this;
        }

        public Builder dollarQuotingSupported(boolean flag) {
            this.dollarQuotingSupported = flag;
            return this;
        }

        public Builder backslashEscapesSupported(boolean flag) {
            this.backslashEscapesSupported = flag;
            return this;
        }

        public Builder hashLineCommentSupported(boolean flag) {
            this.hashLineCommentSupported = flag;
            return this;
        }

        public Builder delimiterDirectiveSupported(boolean flag) {
            this.delimiterDirectiveSupported = flag;
            return this;
        }

        public Builder plsqlBlockDetector(PlsqlBlockDetector detector) {
            this.plsqlBlockDetector = detector;
            return this;
        }

        public Builder defaultStatementSeparator(String separator) {
            this.defaultStatementSeparator = separator;
            return this;
        }

        public SqlStatementBuilderConfig build() {
            return new SqlStatementBuilderConfig(
                    identifierQuoteChar,
                    dollarQuotingSupported,
                    backslashEscapesSupported,
                    hashLineCommentSupported,
                    delimiterDirectiveSupported,
                    plsqlBlockDetector,
                    defaultStatementSeparator);
        }
    }
}
