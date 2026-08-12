package com.flydb.core.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationState;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

/**
 * 迁移信息只读视图（设计 02 §8）。
 *
 * <p>提供 {@code all()} / {@code pending()} / {@code applied()} / {@code current()} 四个视图，
 * 供 CLI 的 {@code info} 表格与 starter 日志复用。不可变。
 */
public final class MigrationInfoService {

    private final List<MigrationInfo> all;

    public MigrationInfoService(List<MigrationInfo> all) {
        this.all = Collections.unmodifiableList(all);
    }

    /** 全部迁移的完整视图（已排序）。 */
    public List<MigrationInfo> all() {
        return all;
    }

    /** 待执行迁移（PENDING + OUT_OF_ORDER + OUTDATED）。 */
    public List<MigrationInfo> pending() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** 已成功应用的迁移（SUCCESS + BASELINE）。 */
    public List<MigrationInfo> applied() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /** 当前版本（已应用最高版本，无则 {@code null}）。 */
    public MigrationVersion current() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}