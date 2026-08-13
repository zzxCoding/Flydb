package com.flydb.core.executor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.placeholder.PlaceholderReplacer;

/**
 * SQL 迁移执行器（设计 04 §1.4）。
 *
 * <p>流程：占位符替换（对原始全文，在词法解析<b>之前</b>，见 05 §9）→ {@link SqlScriptParser#parse(String)} →
 * 逐条 {@link Statement#execute(String)}。失败时异常携带：脚本名、语句序号、起始行号、驱动原始错误。
 *
 * <p>执行器只负责 SQL 路径，不管理事务边界——事务由命令层控制。
 */
public final class SqlMigrationExecutor implements MigrationExecutor {

    private final String scriptName;
    private final String sql;
    private final SqlStatementBuilderConfig parserConfig;
    private final String placeholderPrefix;
    private final String placeholderSuffix;
    private final Map<String, String> placeholders;
    private final Map<String, String> builtIns;

    public SqlMigrationExecutor(String scriptName, String sql,
                                SqlStatementBuilderConfig parserConfig,
                                String placeholderPrefix, String placeholderSuffix,
                                Map<String, String> placeholders,
                                Map<String, String> builtIns) {
        this.scriptName = scriptName;
        this.sql = sql;
        this.parserConfig = parserConfig;
        this.placeholderPrefix = placeholderPrefix;
        this.placeholderSuffix = placeholderSuffix;
        this.placeholders = placeholders;
        this.builtIns = builtIns;
    }

    @Override
    public void execute(Connection connection) throws SQLException {
        List<SqlStatement> statements = statements();

        // 3) 逐条执行
        Statement stmt = connection.createStatement();
        try {
            int index = 1;
            for (SqlStatement statement : statements) {
                try {
                    stmt.execute(statement.sql());
                } catch (SQLException e) {
                    throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                            "脚本 " + scriptName
                                    + " 第 " + index + " 条语句（起始行 " + statement.lineNumber()
                                    + "）执行失败: " + e.getMessage());
                }
                index++;
            }
        } finally {
            try {
                stmt.close();
            } catch (SQLException ignored) {
                // 关闭 Statement 异常不吞噬主异常
            }
        }
    }

    /** 完成与真实执行一致的占位符替换和词法解析，但不触碰 JDBC。 */
    public List<SqlStatement> statements() {
        // 1) 占位符替换
        String resolved = PlaceholderReplacer.replace(sql, scriptName,
                placeholderPrefix, placeholderSuffix, placeholders, builtIns);

        // 2) 词法解析
        SqlScriptParser parser = new SqlScriptParser(parserConfig);
        return parser.parse(resolved);
    }
}
