package com.flydb.core.command;

import java.util.ArrayList;
import java.util.List;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.callback.Event;
import com.flydb.core.exception.FlydbValidationException;
import com.flydb.core.exception.ValidationProblem;
import com.flydb.core.lock.MigrationLock;
import com.flydb.core.log.Log;
import com.flydb.core.log.LogFactory;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.PendingCalculator;
import com.flydb.core.migration.ResolvedMigration;

/**
 * migrate 命令（设计 05 §1）。
 *
 * <p>完整时序：
 * <ol>
 *   <li>取得 Connection</li>
 *   <li>DatabaseTypeRegistry.detect(...) → 方言</li>
 *   <li>database = type.createDatabase(...)</li>
 *   <li>SchemaHistory.ensureExists()</li>
 *   <li>lock.acquire()</li>
 *   <li>applied = SchemaHistory.findAll()</li>
 *   <li>resolved = 汇总全部 MigrationResolver 输出并排序</li>
 *   <li>if (validateOnMigrate) 执行校验</li>
 *   <li>pending = PendingCalculator.compute(resolved, applied, outOfOrder)</li>
 *   <li>for each m in pending: 执行并插入历史记录</li>
 *   <li>返回 MigrateResult</li>
 * </ol>
 *
 */
public final class MigrateCommand {

    private final FlydbConfiguration configuration;

    public MigrateCommand(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public MigrateResult execute() { return execute(null); }

    public MigrateResult execute(com.flydb.core.api.PreparedMigrationPlan expected) {
        long started = System.nanoTime();
        Log log = LogFactory.getLog(MigrateCommand.class);
        try (CommandRuntime runtime = CommandRuntime.open(configuration, true);
             MigrationLock lock = runtime.database().createLock(configuration)) {
            com.flydb.core.api.ExecutionObserver.notify(configuration.executionObserver(),
                    com.flydb.core.api.ExecutionEvent.progress(
                            com.flydb.core.api.ExecutionEvent.Type.WAITING_FOR_LOCK, null, 0, 0));
            lock.acquire();
            List<AppliedMigration> applied = runtime.applied();
            List<ResolvedMigration> migrations = executableMigrations(runtime.resolved());
            configuration.versionSelection().warnFamilyDescendantsExcluded(migrations, log);
            if (configuration.validateOnMigrate()) {
                validate(runtime, applied);
            }
            List<ResolvedMigration> pending = PendingCalculator.compute(
                    migrations, applied, configuration.outOfOrder(),
                    configuration.versionSelection());
            PreparedExecution prepared = expected == null ? null
                    : new PreparedExecution(runtime, pending, "migrate", expected);
            List<String> executed = new ArrayList<String>();
            CommandCallbacks callbacks = CommandCallbacks.create(runtime);
            callbacks.fire(Event.BEFORE_MIGRATE);
            try {
                executePending(runtime, pending, executed, callbacks, log, prepared);
                callbacks.fire(Event.AFTER_MIGRATE);
            } catch (RuntimeException e) {
                callbacks.fire(Event.AFTER_MIGRATE_ERROR);
                throw e;
            }
            return new MigrateResult(executed, targetVersion(applied, pending),
                    elapsedMillis(started), lock.warnings());
        }
    }

    static List<ResolvedMigration> executableMigrations(List<ResolvedMigration> resolved) {
        List<ResolvedMigration> result = new ArrayList<ResolvedMigration>();
        for (ResolvedMigration migration : resolved) {
            if (migration.type() != MigrationType.UNDO_SQL) result.add(migration);
        }
        return result;
    }

    static void validate(CommandRuntime runtime, List<AppliedMigration> applied) {
        List<ValidationProblem> problems = MigrationValidator.validate(
                MigrationInfoAssembler.assemble(runtime.resolved(), applied));
        if (!problems.isEmpty()) throw new FlydbValidationException(problems);
    }

    private static void executePending(CommandRuntime runtime,
                                       List<ResolvedMigration> pending,
                                       List<String> executed,
                                       CommandCallbacks callbacks,
                                       Log log, PreparedExecution prepared) {
        int total = pending.size();
        int index = 1;
        for (ResolvedMigration migration : pending) {
            publish(runtime, com.flydb.core.api.ExecutionEvent.Type.SCRIPT_STARTED,
                    migration.script(), index - 1, total);
            log.info("正在执行迁移 " + index + "/" + total + ": " + migration.script());
            long started = System.nanoTime();
            callbacks.fire(Event.BEFORE_EACH_MIGRATE);
            try {
                if (prepared == null) MigrationCommandSupport.execute(runtime, migration);
                else MigrationCommandSupport.execute(runtime, migration, prepared.executor(migration));
                executed.add(migration.script());
                publish(runtime, com.flydb.core.api.ExecutionEvent.Type.SCRIPT_COMPLETED,
                        migration.script(), index, total);
                log.info("完成迁移 " + index + "/" + total + ": " + migration.script()
                        + "（耗时 " + elapsedMillis(started) + " ms）");
                index++;
                callbacks.fire(Event.AFTER_EACH_MIGRATE);
            } catch (RuntimeException e) {
                callbacks.fire(Event.AFTER_EACH_MIGRATE_ERROR);
                throw e;
            }
        }
    }

    private static void publish(CommandRuntime runtime, com.flydb.core.api.ExecutionEvent.Type type,
                                String script, int confirmed, int total) {
        com.flydb.core.api.ExecutionObserver.notify(runtime.configuration().executionObserver(),
                com.flydb.core.api.ExecutionEvent.progress(type, script, confirmed, total));
    }

    private static MigrationVersion targetVersion(List<AppliedMigration> applied,
                                                  List<ResolvedMigration> executed) {
        MigrationVersion result = null;
        for (AppliedMigration record : applied) {
            if (record.success() && record.version() != null
                    && record.type() != MigrationType.UNDO_SQL
                    && (result == null || record.version().compareTo(result) > 0)) {
                result = record.version();
            }
        }
        for (ResolvedMigration migration : executed) {
            if (migration.version() != null
                    && (result == null || migration.version().compareTo(result) > 0)) {
                result = migration.version();
            }
        }
        return result;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
