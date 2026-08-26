package com.flydb.core.command;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.regex.Pattern;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.executor.SqlMigrationExecutor;
import com.flydb.core.executor.SqlStatement;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.ResolvedMigration;

/** migrate/undo 共用的单脚本执行与历史记账。 */
final class MigrationCommandSupport {

    private static final Pattern TRANSACTION_SAFE_DML = Pattern.compile(
            "^(?:INSERT|UPDATE|DELETE|MERGE)\\b", Pattern.CASE_INSENSITIVE);

    private MigrationCommandSupport() {
    }

    static void execute(final CommandRuntime runtime, final ResolvedMigration migration) {
        SqlMigrationExecutor executor = executor(runtime, migration);
        boolean transactional = runtime.database().supportsDdlTransactions()
                || isTransactionSafeDml(executor.statements(),
                        runtime.database().statementBuilderConfig().hashLineCommentSupported());
        try {
            MigrationExecutionTemplate.execute(runtime.connection(),
                    transactional, executor,
                    (success, elapsed) -> runtime.history().insert(record(
                            runtime, migration, success, elapsed)));
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "脚本 " + migration.script() + " 执行失败: " + e.getMessage(), e);
        }
    }

    static List<SqlStatement> preview(CommandRuntime runtime,
                                      ResolvedMigration migration) {
        return executor(runtime, migration).statements();
    }

    private static boolean isTransactionSafeDml(List<SqlStatement> statements,
                                                boolean hashLineComments) {
        if (statements.isEmpty()) return false;
        boolean hasDml = false;
        for (SqlStatement statement : statements) {
            String executableSql = stripLeadingComments(statement.sql(), hashLineComments);
            if (executableSql.isEmpty()) continue;
            hasDml = true;
            if (!TRANSACTION_SAFE_DML.matcher(executableSql).find()) return false;
        }
        return hasDml;
    }

    private static String stripLeadingComments(String sql, boolean hashLineComments) {
        int offset = 0;
        while (offset < sql.length()) {
            offset = skipWhitespace(sql, offset);
            if (startsWith(sql, offset, "--")
                    || (hashLineComments && startsWith(sql, offset, "#"))) {
                offset = skipLineComment(sql, offset);
            } else if (startsWith(sql, offset, "/*")) {
                int end = sql.indexOf("*/", offset + 2);
                if (end < 0) return "";
                offset = end + 2;
            } else {
                break;
            }
        }
        return sql.substring(offset);
    }

    private static int skipWhitespace(String sql, int offset) {
        while (offset < sql.length() && Character.isWhitespace(sql.charAt(offset))) offset++;
        return offset;
    }

    private static int skipLineComment(String sql, int offset) {
        while (offset < sql.length()) {
            char current = sql.charAt(offset++);
            if (current == '\n' || current == '\r') break;
        }
        return offset;
    }

    private static boolean startsWith(String sql, int offset, String prefix) {
        return sql.regionMatches(offset, prefix, 0, prefix.length());
    }

    private static SqlMigrationExecutor executor(CommandRuntime runtime,
                                                 ResolvedMigration migration) {
        String sql = ScriptLoader.load(runtime.configuration(), migration.script());
        return new SqlMigrationExecutor(migration.script(), sql,
                runtime.database().statementBuilderConfig(),
                runtime.configuration().placeholderReplacement(),
                runtime.configuration().placeholderPrefix(),
                runtime.configuration().placeholderSuffix(),
                runtime.configuration().placeholders(), runtime.builtIns())
                .batchSize(runtime.configuration().batchSize());
    }

    private static AppliedMigration record(CommandRuntime runtime,
                                           ResolvedMigration migration,
                                           boolean success, int elapsed) {
        try {
            return AppliedMigration.of(0, migration.version(), migration.description(),
                    migration.type(), migration.script(), migration.checksum(),
                    runtime.database().currentUser(), new Timestamp(System.currentTimeMillis()),
                    elapsed, success);
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "读取 installed_by 失败: " + e.getMessage(), e);
        }
    }
}
