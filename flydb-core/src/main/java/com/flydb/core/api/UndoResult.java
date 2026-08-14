package com.flydb.core.api;

import com.flydb.core.migration.MigrationVersion;

/** 最近一次版本化迁移的撤销结果。 */
public final class UndoResult {
    private final MigrationVersion undoneVersion;
    private final long executionTimeMillis;

    public UndoResult(MigrationVersion undoneVersion, long executionTimeMillis) {
        this.undoneVersion = undoneVersion;
        this.executionTimeMillis = executionTimeMillis;
    }

    public MigrationVersion undoneVersion() { return undoneVersion; }
    public long executionTimeMillis() { return executionTimeMillis; }
}
