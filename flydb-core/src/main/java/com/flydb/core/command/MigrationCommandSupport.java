package com.flydb.core.command;

import java.sql.SQLException;
import java.sql.Timestamp;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.executor.MigrationExecutor;
import com.flydb.core.executor.SqlMigrationExecutor;
import com.flydb.core.executor.SqlStatement;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.ResolvedMigration;

/** migrate/undo 共用的单脚本执行与历史记账。 */
final class MigrationCommandSupport {

    private MigrationCommandSupport() {
    }

    static void execute(final CommandRuntime runtime, final ResolvedMigration migration) {
        MigrationExecutor executor = executor(runtime, migration);
        try {
            MigrationExecutionTemplate.execute(runtime.connection(),
                    runtime.database().supportsDdlTransactions(), executor,
                    (success, elapsed) -> runtime.history().insert(record(
                            runtime, migration, success, elapsed)));
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "脚本 " + migration.script() + " 执行失败: " + e.getMessage(), e);
        }
    }

    static java.util.List<SqlStatement> preview(CommandRuntime runtime,
                                                ResolvedMigration migration) {
        return executor(runtime, migration).statements();
    }

    private static SqlMigrationExecutor executor(CommandRuntime runtime,
                                                 ResolvedMigration migration) {
        String sql = ScriptLoader.load(runtime.configuration(), migration.script());
        return new SqlMigrationExecutor(migration.script(), sql,
                runtime.database().statementBuilderConfig(),
                runtime.configuration().placeholderReplacement(),
                runtime.configuration().placeholderPrefix(),
                runtime.configuration().placeholderSuffix(),
                runtime.configuration().placeholders(), runtime.builtIns());
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
