package com.flydb.core.api;

/** dry-run 输出中的单条 SQL，保留原脚本起始行用于评审定位。 */
public final class DryRunStatement {
    private final int lineNumber;
    private final String sql;

    public DryRunStatement(int lineNumber, String sql) {
        if (lineNumber < 1) throw new IllegalArgumentException("lineNumber must be >= 1");
        if (sql == null) throw new NullPointerException("sql");
        this.lineNumber = lineNumber;
        this.sql = sql;
    }

    public int lineNumber() { return lineNumber; }
    public String sql() { return sql; }
}
