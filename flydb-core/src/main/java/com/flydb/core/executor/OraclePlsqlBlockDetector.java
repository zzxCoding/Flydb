package com.flydb.core.executor;

import java.util.regex.Pattern;

/**
 * Oracle 家族内置 PL/SQL 块探测器。
 *
 * <p>识别以下开头（大小写不敏感，作用于去注释+大写+trim 后的语句文本）：
 * <ul>
 *   <li>{@code CREATE [OR REPLACE] PROCEDURE|FUNCTION|PACKAGE[ BODY]|TRIGGER|TYPE}</li>
 *   <li>裸 {@code DECLARE} / {@code BEGIN}</li>
 * </ul>
 *
 * <p>不可变、无状态，提供单例 {@link #INSTANCE}。
 *
 * @see PlsqlBlockDetector
 */
public final class OraclePlsqlBlockDetector implements PlsqlBlockDetector {

    /** 单例（无状态，可安全共享）。 */
    public static final OraclePlsqlBlockDetector INSTANCE = new OraclePlsqlBlockDetector();

    private static final Pattern PLSQL_START = Pattern.compile(
            "^("
                    + "CREATE(?:\\s+OR\\s+REPLACE)?\\s+"
                    + "(?:PROCEDURE|FUNCTION|PACKAGE\\s+BODY|PACKAGE|TRIGGER|TYPE)\\b"
                    + "|DECLARE\\b"
                    + "|BEGIN\\b"
                    + ").*",
            Pattern.DOTALL);

    private OraclePlsqlBlockDetector() {
    }

    @Override
    public boolean startsPlsqlBlock(String statementSoFarUpperTrimmed) {
        if (statementSoFarUpperTrimmed == null || statementSoFarUpperTrimmed.isEmpty()) {
            return false;
        }
        return PLSQL_START.matcher(statementSoFarUpperTrimmed).matches();
    }
}
