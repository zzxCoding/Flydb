package com.flydb.core.command;

import java.util.ArrayList;
import java.util.List;

import com.flydb.core.api.DryRunMigration;
import com.flydb.core.api.DryRunResult;
import com.flydb.core.api.DryRunStatement;
import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.executor.SqlStatement;
import com.flydb.core.log.Log;
import com.flydb.core.log.LogFactory;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.PendingCalculator;
import com.flydb.core.migration.ResolvedMigration;

/** migrate/undo 的只读预演：探测、校验、解析和 pending 计算与真实命令一致。 */
public final class DryRunCommand {

    private final FlydbConfiguration configuration;

    public DryRunCommand(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public DryRunResult migrate() { return prepareMigrate().preview(); }

    public com.flydb.core.api.PreparedMigrationPlan prepareMigrate() {
        Log log = LogFactory.getLog(DryRunCommand.class);
        try (CommandRuntime runtime = CommandRuntime.open(configuration, false)) {
            List<AppliedMigration> applied = runtime.applied();
            List<ResolvedMigration> migrations =
                    MigrateCommand.executableMigrations(runtime.resolved());
            configuration.versionSelection().warnFamilyDescendantsExcluded(migrations, log);
            if (configuration.validateOnMigrate()) {
                MigrateCommand.validate(runtime, applied);
            }
            return new PreparedExecution(runtime, PendingCalculator.compute(
                    migrations, applied, configuration.outOfOrder(),
                    configuration.versionSelection()), "migrate", null).plan();
        }
    }

    public DryRunResult undo() { return prepareUndo().preview(); }

    public com.flydb.core.api.PreparedMigrationPlan prepareUndo() {
        try (CommandRuntime runtime = CommandRuntime.open(configuration, false)) {
            ResolvedMigration undo = UndoCommand.findUndo(runtime.resolved(),
                    UndoCommand.latestAppliedVersion(runtime.applied()));
            return new PreparedExecution(runtime, java.util.Collections.singletonList(undo), "undo", null).plan();
        }
    }

}
