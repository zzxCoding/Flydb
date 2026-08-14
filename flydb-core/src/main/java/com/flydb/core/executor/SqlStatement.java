package com.flydb.core.executor;

import java.util.Objects;

/**
 * 一条切分后的 SQL 语句。
 *
 * <p>由 {@link SqlScriptLexer} 产出。{@link #lineNumber()} 记录该语句在原始脚本中的起始行号
 * （1-based，指向首段实际代码所在行），供 {@code MigrationExecutor} 在执行失败时将驱动原始错误
 * 定位到具体脚本行（设计 04 §1.4）。
 *
 * <p>不可变值对象。
 */
public final class SqlStatement {

    private final String sql;
    private final int lineNumber;

    /**
     * @param sql        语句文本（已 trim）
     * @param lineNumber 起始行号（1-based，必须 ≥ 1）
     */
    public SqlStatement(String sql, int lineNumber) {
        if (sql == null) {
            throw new NullPointerException("sql must not be null");
        }
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be >= 1, got " + lineNumber);
        }
        this.sql = sql;
        this.lineNumber = lineNumber;
    }

    /** 语句文本（已 trim）。 */
    public String sql() {
        return sql;
    }

    /** 在原始脚本中的起始行号（1-based）。 */
    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SqlStatement)) {
            return false;
        }
        SqlStatement that = (SqlStatement) o;
        return lineNumber == that.lineNumber && sql.equals(that.sql);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sql, lineNumber);
    }

    @Override
    public String toString() {
        return "SqlStatement{line=" + lineNumber + ", sql='" + sql + "'}";
    }
}
