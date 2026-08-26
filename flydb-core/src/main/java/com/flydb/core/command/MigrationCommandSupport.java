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
                || isTransactionSafeDml(executor.statements());
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

    private static boolean isTransactionSafeDml(List<SqlStatement> statements) {
        if (statements.isEmpty()) return false;
        for (SqlStatement statement : statements) {
            if (!TRANSACTION_SAFE_DML.matcher(statement.sql()).find()) return false;
        }
        return true;
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
