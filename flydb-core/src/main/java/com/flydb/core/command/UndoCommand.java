package com.flydb.core.command;

import java.util.List;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.UndoResult;
import com.flydb.core.callback.Event;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.lock.MigrationLock;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

/** undo：仅撤销当前最高的已应用版本化迁移。 */
public final class UndoCommand {
    private final FlydbConfiguration configuration;

    public UndoCommand(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public UndoResult execute() {
        long started = System.nanoTime();
        try (CommandRuntime runtime = CommandRuntime.open(configuration, true);
             MigrationLock lock = runtime.database().createLock(configuration)) {
            lock.acquire();
            MigrationVersion latest = latestAppliedVersion(runtime.applied());
            ResolvedMigration undo = findUndo(runtime.resolved(), latest);
            CommandCallbacks callbacks = CommandCallbacks.create(runtime);
            callbacks.fire(Event.BEFORE_UNDO);
            MigrationCommandSupport.execute(runtime, undo);
            callbacks.fire(Event.AFTER_UNDO);
            return new UndoResult(latest, elapsedMillis(started));
        }
    }

    private static MigrationVersion latestAppliedVersion(List<AppliedMigration> applied) {
        java.util.Map<MigrationVersion, AppliedMigration> latest =
                new java.util.HashMap<MigrationVersion, AppliedMigration>();
        for (AppliedMigration record : applied) {
            if (record.version() == null || !record.success()
                    || record.type() == MigrationType.BASELINE) continue;
            AppliedMigration old = latest.get(record.version());
            if (old == null || record.installedRank() > old.installedRank()) {
                latest.put(record.version(), record);
            }
        }
        MigrationVersion result = null;
        for (java.util.Map.Entry<MigrationVersion, AppliedMigration> entry : latest.entrySet()) {
            if (entry.getValue().type() == MigrationType.UNDO_SQL) continue;
            if (result == null || entry.getKey().compareTo(result) > 0) result = entry.getKey();
        }
        if (result == null) {
            throw new FlydbException(ErrorCode.MISSING_UNDO_SCRIPT,
                    "当前没有可撤销的已应用版本化迁移");
        }
        return result;
    }

    private static ResolvedMigration findUndo(List<ResolvedMigration> resolved,
                                              MigrationVersion version) {
        for (ResolvedMigration migration : resolved) {
            if (migration.type() == MigrationType.UNDO_SQL
                    && version.equals(migration.version())) return migration;
        }
        throw new FlydbException(ErrorCode.MISSING_UNDO_SCRIPT,
                "最近版本 " + version + " 缺少 U" + version + "__*.sql");
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
