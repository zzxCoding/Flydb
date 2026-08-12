package com.flydb.core.command;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.RepairResult;
import com.flydb.core.callback.Event;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.lock.MigrationLock;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.ResolvedMigration;

/** repair：仅修复历史记账，不改变 schema。 */
public final class RepairCommand {
    private final FlydbConfiguration configuration;

    public RepairCommand(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public RepairResult execute() {
        try (CommandRuntime runtime = CommandRuntime.open(configuration, true);
             MigrationLock lock = runtime.database().createLock(configuration)) {
            lock.acquire();
            CommandCallbacks callbacks = CommandCallbacks.create(runtime);
            callbacks.fire(Event.BEFORE_REPAIR);
            List<AppliedMigration> applied = runtime.applied();
            begin(runtime);
            try {
                List<String> removed = runtime.history().deleteFailed(applied);
                List<String> aligned = alignChecksums(runtime, applied);
                commit(runtime);
                callbacks.fire(Event.AFTER_REPAIR);
                return new RepairResult(removed, aligned);
            } catch (RuntimeException e) {
                rollback(runtime);
                throw e;
            }
        }
    }

    private static List<String> alignChecksums(CommandRuntime runtime,
                                               List<AppliedMigration> applied) {
        List<String> aligned = new ArrayList<String>();
        for (ResolvedMigration local : runtime.resolved()) {
            if (local.type() == MigrationType.UNDO_SQL || local.version() == null) continue;
            AppliedMigration recorded = latestSuccessful(applied, local);
            if (recorded != null && !equals(local.checksum(), recorded.checksum())) {
                runtime.history().updateChecksum(recorded.script(), local.checksum());
                aligned.add(recorded.script());
            }
        }
        return aligned;
    }

    private static AppliedMigration latestSuccessful(List<AppliedMigration> applied,
                                                      ResolvedMigration local) {
        AppliedMigration result = null;
        for (AppliedMigration record : applied) {
            if (record.success() && record.version() != null
                    && record.version().equals(local.version())
                    && record.type() != MigrationType.UNDO_SQL
                    && (result == null || record.installedRank() > result.installedRank())) {
                result = record;
            }
        }
        return result;
    }

    private static boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void begin(CommandRuntime runtime) {
        try { runtime.connection().setAutoCommit(false); }
        catch (SQLException e) { throw failure("开启 repair 事务", e); }
    }

    private static void commit(CommandRuntime runtime) {
        try { runtime.connection().commit(); runtime.connection().setAutoCommit(true); }
        catch (SQLException e) { throw failure("提交 repair 事务", e); }
    }

    private static void rollback(CommandRuntime runtime) {
        try { runtime.connection().rollback(); runtime.connection().setAutoCommit(true); }
        catch (SQLException ignored) { }
    }

    private static FlydbException failure(String action, SQLException e) {
        return new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                action + "失败: " + e.getMessage(), e);
    }
}
