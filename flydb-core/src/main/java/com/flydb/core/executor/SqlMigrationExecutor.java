package com.flydb.core.executor;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
 * <p>{@code batchSize > 1} 时改用 JDBC batch（{@code addBatch}/{@code executeBatch}），
 * 减少远程库逐条往返；失败语句优先按 {@link Statement#EXECUTE_FAILED} 标记定位，
 * 遇错即停的驱动再按已执行计数推算，驱动没有提供可靠标记时只报告批次范围。
 * 默认 {@code batchSize=1} 保持逐条执行与精确定位。
 *
 * <p>执行器只负责 SQL 路径，不管理事务边界——事务由命令层控制。
 */
public final class SqlMigrationExecutor implements MigrationExecutor {

    private final String scriptName;
    private final String sql;
    private final SqlStatementBuilderConfig parserConfig;
    private final boolean placeholderReplacement;
    private final String placeholderPrefix;
    private final String placeholderSuffix;
    private final Map<String, String> placeholders;
    private final Map<String, String> builtIns;
    private int batchSize = 1;

    public SqlMigrationExecutor(String scriptName, String sql,
                                SqlStatementBuilderConfig parserConfig,
                                String placeholderPrefix, String placeholderSuffix,
                                Map<String, String> placeholders,
                                Map<String, String> builtIns) {
        this(scriptName, sql, parserConfig, true, placeholderPrefix, placeholderSuffix,
                placeholders, builtIns);
    }

    public SqlMigrationExecutor(String scriptName, String sql,
                                SqlStatementBuilderConfig parserConfig,
                                boolean placeholderReplacement,
                                String placeholderPrefix, String placeholderSuffix,
                                Map<String, String> placeholders,
                                Map<String, String> builtIns) {
        this.scriptName = scriptName;
        this.sql = sql;
        this.parserConfig = parserConfig;
        this.placeholderReplacement = placeholderReplacement;
        this.placeholderPrefix = placeholderPrefix;
        this.placeholderSuffix = placeholderSuffix;
        this.placeholders = placeholders;
        this.builtIns = builtIns;
    }

    /** 设置 JDBC 批大小；必须在 {@link #execute} 前调用。 */
    public SqlMigrationExecutor batchSize(int size) {
        this.batchSize = size;
        return this;
    }

    @Override
    public void execute(Connection connection) throws SQLException {
        List<SqlStatement> statements = statements();

        Statement stmt = connection.createStatement();
        try {
            if (batchSize <= 1) {
                executeOneByOne(stmt, statements);
            } else {
                executeInBatches(stmt, statements);
            }
        } finally {
            try {
                stmt.close();
            } catch (SQLException ignored) {
                // 关闭 Statement 异常不吞噬主异常
            }
        }
    }

    /** 默认路径：逐条执行，失败精确定位到语句序号与起始行号。 */
    private void executeOneByOne(Statement stmt, List<SqlStatement> statements)
            throws SQLException {
        int index = 1;
        for (SqlStatement statement : statements) {
            try {
                stmt.execute(statement.sql());
            } catch (SQLException e) {
                throw failure(index, statement, e);
            }
            index++;
        }
    }

    /** batch 路径：每 batchSize 条语句一次往返。 */
    private void executeInBatches(Statement stmt, List<SqlStatement> statements)
            throws SQLException {
        int index = 1;
        List<SqlStatement> buffer = new ArrayList<SqlStatement>(batchSize);
        for (SqlStatement statement : statements) {
            buffer.add(statement);
            if (buffer.size() >= batchSize) {
                index = flush(stmt, buffer, index);
            }
        }
        if (!buffer.isEmpty()) {
            flush(stmt, buffer, index);
        }
    }

    private int flush(Statement stmt, List<SqlStatement> buffer, int startIndex)
            throws SQLException {
        for (SqlStatement statement : buffer) {
            stmt.addBatch(statement.sql());
        }
        int nextIndex = startIndex + buffer.size();
        try {
            stmt.executeBatch();
        } catch (BatchUpdateException e) {
            throw batchFailure(startIndex, buffer, e, e.getUpdateCounts());
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "脚本 " + scriptName + " 第 " + startIndex + "-" + (nextIndex - 1)
                            + " 条语句批量执行失败（batch-size=" + batchSize + "）: "
                            + e.getMessage(), e);
        }
        stmt.clearBatch();
        buffer.clear();
        return nextIndex;
    }

    private FlydbException batchFailure(int startIndex, List<SqlStatement> buffer,
                                        SQLException cause, int[] updateCounts) {
        int failedOffset = firstFailedOffset(updateCounts, buffer.size());
        if (failedOffset >= 0) {
            SqlStatement statement = buffer.get(failedOffset);
            return new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "脚本 " + scriptName + " 第 " + (startIndex + failedOffset)
                            + " 条语句（起始行 " + statement.lineNumber()
                            + "）执行失败（batch-size=" + batchSize
                            + "，由 JDBC EXECUTE_FAILED 标记定位）: "
                            + cause.getMessage(), cause);
        }

        // 遇错即停的驱动只返回失败前的计数，此时数组长度就是失败语句的批内偏移。
        int applied = updateCounts == null ? 0 : updateCounts.length;
        if (applied < buffer.size()) {
            SqlStatement statement = buffer.get(applied);
            return new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "脚本 " + scriptName + " 第 " + (startIndex + applied)
                            + " 条语句（起始行 " + statement.lineNumber()
                            + "）执行失败（batch-size=" + batchSize
                            + "，序号按批内已执行计数推算）: "
                            + cause.getMessage(), cause);
        }

        int endIndex = startIndex + buffer.size() - 1;
        return new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                "脚本 " + scriptName + " 第 " + startIndex + "-" + endIndex
                        + " 条语句批量执行失败（batch-size=" + batchSize
                        + "，JDBC 驱动未提供可识别的 EXECUTE_FAILED 标记，"
                        + "无法可靠定位具体语句与行号）: " + cause.getMessage(), cause);
    }

    private static int firstFailedOffset(int[] updateCounts, int batchLength) {
        if (updateCounts == null) return -1;
        int length = Math.min(updateCounts.length, batchLength);
        for (int i = 0; i < length; i++) {
            if (updateCounts[i] == Statement.EXECUTE_FAILED) {
                return i;
            }
        }
        return -1;
    }

    private FlydbException failure(int index, SqlStatement statement, SQLException cause) {
        return new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                "脚本 " + scriptName + " 第 " + index + " 条语句（起始行 "
                        + statement.lineNumber() + "）执行失败: " + cause.getMessage(), cause);
    }

    /** 完成与真实执行一致的占位符替换和词法解析，但不触碰 JDBC。 */
    public List<SqlStatement> statements() {
        // 1) 占位符替换
        String resolved = placeholderReplacement
                ? PlaceholderReplacer.replace(sql, scriptName,
                        placeholderPrefix, placeholderSuffix, placeholders, builtIns)
                : sql;

        // 2) 词法解析
        SqlScriptParser parser = new SqlScriptParser(parserConfig);
        return parser.parse(resolved);
    }
}
