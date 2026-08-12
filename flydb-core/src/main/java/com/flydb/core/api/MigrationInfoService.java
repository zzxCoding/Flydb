package com.flydb.core.api;

import java.util.Arrays;
import java.util.ArrayList;
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
        this.all = Collections.unmodifiableList(new ArrayList<MigrationInfo>(all));
    }

    /** 全部迁移的完整视图（已排序）。 */
    public List<MigrationInfo> all() {
        return all;
    }

    /** 待执行迁移（PENDING + OUT_OF_ORDER + OUTDATED）。 */
    public List<MigrationInfo> pending() {
        return filter(MigrationState.PENDING, MigrationState.OUT_OF_ORDER, MigrationState.OUTDATED);
    }

    /** 已成功应用的迁移（SUCCESS + BASELINE）。 */
    public List<MigrationInfo> applied() {
        return filter(MigrationState.SUCCESS, MigrationState.BASELINE);
    }

    /** 当前版本（已应用最高版本，无则 {@code null}）。 */
    public MigrationVersion current() {
        MigrationVersion current = null;
        for (MigrationInfo info : all) {
            if (info.state() != MigrationState.SUCCESS && info.state() != MigrationState.BASELINE) {
                continue;
            }
            AppliedMigration applied = info.applied();
            MigrationVersion version = applied == null ? null : applied.version();
            if (version != null && (current == null || version.compareTo(current) > 0)) {
                current = version;
            }
        }
        return current;
    }

    private List<MigrationInfo> filter(MigrationState... states) {
        List<MigrationState> accepted = Arrays.asList(states);
        List<MigrationInfo> result = new ArrayList<MigrationInfo>();
        for (MigrationInfo info : all) {
            if (accepted.contains(info.state())) {
                result.add(info);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
