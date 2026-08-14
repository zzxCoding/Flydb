package com.flydb.core.executor;

/**
 * PL/SQL 块边界探测器（Oracle 家族专用）。
 *
 * <p>由 {@link SqlScriptLexer} 在 {@code DEFAULT} 状态遇到候选终止符（默认 {@code ;}）时调用，
 * 输入为当前语句已累积文本（去注释、大写、trim）。若返回 {@code true}，词法器不产出语句，
 * 而是切换到 {@code IN_PLSQL_BLOCK} 状态——后续 {@code ;} 视作块内分隔，唯一终止符变为
 * 独占一行的 {@code /}（见设计 04 §1.2/§1.3）。
 *
 * <p>家族差异以配置数据表达，不以代码分支表达：仅 Oracle 家族预设非 null 实例，
 * PostgreSQL/MySQL 家族为 null（不支持 PL/SQL 块）。
 *
 * @see OraclePlsqlBlockDetector
 */
public interface PlsqlBlockDetector {

    /**
     * 判定当前已累积的语句文本是否开启一个 PL/SQL 块。
     *
     * @param statementSoFarUpperTrimmed 当前语句已累积文本（注释已剥离、已大写、已 trim）
     * @return 若以 CREATE [OR REPLACE] PROCEDURE/FUNCTION/PACKAGE[ BODY]/TRIGGER/TYPE，
     *         或裸 DECLARE / BEGIN 开头则返回 true
     */
    boolean startsPlsqlBlock(String statementSoFarUpperTrimmed);
}
