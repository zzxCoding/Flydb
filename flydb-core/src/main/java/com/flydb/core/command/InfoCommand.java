package com.flydb.core.command;

import com.flydb.core.api.MigrationInfoService;

/**
 * info 命令（设计 05 §2）。
 *
 * <p>只读，不加锁。返回 resolved × applied 全外连接，每条推导状态。
 * 历史表不存在时不报错，返回全部 PENDING（配合 CLI 首次体验）。
 */
public final class InfoCommand {

    private final com.flydb.core.api.FlydbConfiguration configuration;

    public InfoCommand(com.flydb.core.api.FlydbConfiguration configuration) {
        this.configuration = configuration;
    }

    public MigrationInfoService execute() {
        try (CommandRuntime runtime = CommandRuntime.open(configuration, false)) {
            return new MigrationInfoService(MigrationInfoAssembler.assemble(
                    runtime.resolved(), runtime.applied()));
        }
    }
}
