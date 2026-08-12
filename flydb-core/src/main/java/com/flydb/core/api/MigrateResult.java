package com.flydb.core.api;

import java.util.Collections;
import java.util.List;

import com.flydb.core.migration.MigrationVersion;

/**
 * migrate 命令结果（设计 02 §8）。
 *
 * <p>不可变值对象，包含本次执行的迁移列表、达到的版本、总耗时与警告。
 */
public final class MigrateResult {

    private final List<String> executed;
    private final MigrationVersion targetVersionReached;
    private final long totalExecutionTimeMillis;
    private final List<String> warnings;

    public MigrateResult(List<String> executed, MigrationVersion targetVersionReached,
                         long totalExecutionTimeMillis, List<String> warnings) {
        this.executed = Collections.unmodifiableList(executed);
        this.targetVersionReached = targetVersionReached;
        this.totalExecutionTimeMillis = totalExecutionTimeMillis;
        this.warnings = warnings != null
                ? Collections.unmodifiableList(warnings)
                : Collections.<String>emptyList();
    }

    public List<String> executed() { return executed; }
    public MigrationVersion targetVersionReached() { return targetVersionReached; }
    public long totalExecutionTimeMillis() { return totalExecutionTimeMillis; }
    public List<String> warnings() { return warnings; }
}