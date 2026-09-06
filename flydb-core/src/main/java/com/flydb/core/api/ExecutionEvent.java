package com.flydb.core.api;

/** Immutable observations from the executing code; contains no SQL or credentials. */
public final class ExecutionEvent {
    public enum Type { WAITING_FOR_LOCK, SCRIPT_STARTED, SQL_PROGRESS, SQL_FAILURE,
        TRANSACTION_RESULT, SCRIPT_COMPLETED }

    private final Type type;
    private final String script;
    private final int confirmed;
    private final int total;
    private final String phase;
    private final String transaction;
    private final int failureStart;
    private final int failureEnd;
    private final int lineNumber;

    private ExecutionEvent(Type type, String script, int confirmed, int total, String phase,
                           String transaction, int failureStart, int failureEnd, int lineNumber) {
        this.type = type; this.script = script; this.confirmed = confirmed; this.total = total;
        this.phase = phase; this.transaction = transaction; this.failureStart = failureStart;
        this.failureEnd = failureEnd; this.lineNumber = lineNumber;
    }

    public static ExecutionEvent progress(Type type, String script, int confirmed, int total) {
        return new ExecutionEvent(type, script, confirmed, total, null, null, 0, 0, 0);
    }
    public static ExecutionEvent transaction(String script, String phase, String result) {
        return new ExecutionEvent(Type.TRANSACTION_RESULT, script, 0, 0, phase, result, 0, 0, 0);
    }
    public static ExecutionEvent failure(String script, int confirmed, int total,
                                         int start, int end, int line, String confidence) {
        return new ExecutionEvent(Type.SQL_FAILURE, script, confirmed, total,
                confidence, null, start, end, line);
    }
    public Type type() { return type; }
    public String script() { return script; }
    /** SQL_PROGRESS: continuous JDBC successes; script events: completed script count. */
    public int confirmed() { return confirmed; }
    public int total() { return total; }
    public String phase() { return phase; }
    public String transaction() { return transaction; }
    public int failureStart() { return failureStart; }
    public int failureEnd() { return failureEnd; }
    public int lineNumber() { return lineNumber; }
}
