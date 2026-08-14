package com.flydb.core.command;

import java.sql.SQLException;
import java.util.Arrays;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.callback.Event;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.lock.MigrationLock;
import com.flydb.core.log.Log;
import com.flydb.core.log.LogFactory;

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
        Log log = LogFactory.getLog(CleanCommand.class);
        try (CommandRuntime runtime = CommandRuntime.open(configuration, true);
             MigrationLock lock = runtime.database().createLock(configuration)) {
            lock.acquire();
            CommandCallbacks callbacks = CommandCallbacks.create(runtime);
            callbacks.fire(Event.BEFORE_CLEAN);
            String schema = clean(runtime, log);
            callbacks.fire(Event.AFTER_CLEAN);
            lock.release();
            dropBookkeepingTables(runtime, log);
            log.info("clean 完成：schema " + schema);
        }
    }

    private static String clean(CommandRuntime runtime, Log log) {
        boolean transactional = runtime.database().supportsDdlTransactions();
        try {
            String schema = runtime.database().currentSchema();
            log.info("开始清理 schema " + schema);
            if (transactional) runtime.connection().setAutoCommit(false);
            runtime.database().cleanStrategy().clean(runtime.connection(),
                    schema, Arrays.asList(
                            configurationTable(runtime), lockTable(runtime)));
            if (transactional) runtime.connection().commit();
            return schema;
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

    private static void dropBookkeepingTables(CommandRuntime runtime, Log log) {
        log.info("正在删除历史表: " + configurationTable(runtime));
        runtime.history().dropHistoryTable();
        log.info("正在删除锁表: " + lockTable(runtime));
        runtime.history().dropLockTable();
    }

    private static void rollback(CommandRuntime runtime) {
        try { runtime.connection().rollback(); } catch (SQLException ignored) { }
    }

    private static void restoreAutoCommit(CommandRuntime runtime) {
        try { runtime.connection().setAutoCommit(true); } catch (SQLException ignored) { }
    }
}
