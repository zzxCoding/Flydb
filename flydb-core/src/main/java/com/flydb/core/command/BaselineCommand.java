package com.flydb.core.command;

import java.sql.SQLException;
import java.sql.Timestamp;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.callback.Event;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.lock.MigrationLock;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationType;

/** baseline：在空历史表写入一条合成记录。 */
public final class BaselineCommand {
    private final FlydbConfiguration configuration;

    public BaselineCommand(FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public void execute() {
        try (CommandRuntime runtime = CommandRuntime.open(configuration, true);
             MigrationLock lock = runtime.database().createLock(configuration)) {
            lock.acquire();
            if (!runtime.applied().isEmpty()) {
                throw new FlydbException(ErrorCode.BASELINE_PRECONDITION_UNMET,
                        "历史表已存在迁移记录，baseline 不允许覆盖");
            }
            CommandCallbacks callbacks = CommandCallbacks.create(runtime);
            callbacks.fire(Event.BEFORE_BASELINE);
            runtime.history().insert(record(runtime));
            callbacks.fire(Event.AFTER_BASELINE);
        }
    }

    private AppliedMigration record(CommandRuntime runtime) {
        try {
            return AppliedMigration.of(0, configuration.baselineVersion(), "baseline",
                    MigrationType.BASELINE, "<< BASELINE >>", null,
                    runtime.database().currentUser(), new Timestamp(System.currentTimeMillis()),
                    0, true);
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED,
                    "读取 baseline 用户失败: " + e.getMessage(), e);
        }
    }
}
