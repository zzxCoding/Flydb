package com.flydb.core.command;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.callback.Event;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.lock.MigrationLock;

/** clean：默认禁用，显式开启后清理当前 schema 的表、视图和序列。 */
public final class CleanCommand {
    private final FlydbConfiguration configuration;

    public CleanCommand(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public void execute() {
        if (configuration.cleanDisabled()) {
            throw new FlydbException(ErrorCode.CLEAN_DISABLED,
                    "flydb.clean-disabled=true，未建立数据库连接");
        }
        try (CommandRuntime runtime = CommandRuntime.open(configuration, true);
             MigrationLock lock = runtime.database().createLock(configuration)) {
            lock.acquire();
            CommandCallbacks callbacks = CommandCallbacks.create(runtime);
            callbacks.fire(Event.BEFORE_CLEAN);
            clean(runtime);
            callbacks.fire(Event.AFTER_CLEAN);
            lock.release();
            dropBookkeepingTables(runtime);
        }
    }

    private static void clean(CommandRuntime runtime) {
        boolean transactional = runtime.database().supportsDdlTransactions();
        try {
            if (transactional) runtime.connection().setAutoCommit(false);
            runtime.database().cleanStrategy().clean(runtime.connection(),
                    runtime.database().currentSchema(), Arrays.asList(
                            configurationTable(runtime), lockTable(runtime)));
            if (transactional) runtime.connection().commit();
        } catch (SQLException e) {
            if (transactional) rollback(runtime);
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "clean 执行失败: " + e.getMessage(), e);
        } finally {
            if (transactional) restoreAutoCommit(runtime);
        }
    }

    private static String configurationTable(CommandRuntime runtime) {
        return runtime.configuration().table();
    }

    private static String lockTable(CommandRuntime runtime) {
        return com.flydb.core.history.SchemaHistory.lockTableName(configurationTable(runtime));
    }

    private static void dropBookkeepingTables(CommandRuntime runtime) {
        Statement statement = null;
        try {
            statement = runtime.connection().createStatement();
            statement.execute("DROP TABLE " + runtime.database().quote(configurationTable(runtime)));
            statement.execute("DROP TABLE " + runtime.database().quote(lockTable(runtime)));
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "删除 clean 记账表失败: " + e.getMessage(), e);
        } finally {
            if (statement != null) {
                try { statement.close(); } catch (SQLException ignored) { }
            }
        }
    }

    private static void rollback(CommandRuntime runtime) {
        try { runtime.connection().rollback(); } catch (SQLException ignored) { }
    }

    private static void restoreAutoCommit(CommandRuntime runtime) {
        try { runtime.connection().setAutoCommit(true); } catch (SQLException ignored) { }
    }
}
