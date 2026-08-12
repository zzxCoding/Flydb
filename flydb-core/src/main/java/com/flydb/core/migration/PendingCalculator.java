package com.flydb.core.migration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/**
 * 待执行迁移计算器（设计 05 §1.1）。
 *
 * <p>纯函数：给定本地解析的迁移（已按版本/描述排序）与历史表已应用记录，算出本次 migrate 应执行的列表。
 * 规则：
 * <ol>
 *   <li>FAILED 阻断：applied 存在 {@code success=false} → {@link ErrorCode#FAILED_MIGRATION_NEEDS_REPAIR}（FLYDB-2004）。</li>
 *   <li>baseline 过滤：resolved 版本 ≤ baselineVersion → 跳过（IGNORED）。</li>
 *   <li>outOfOrder：版本低于「当前已应用最高版本」的未执行迁移——{@code outOfOrder=false} 报
 *       {@link ErrorCode#OUT_OF_ORDER_MIGRATION}（FLYDB-2006），{@code true} 则按序执行。</li>
 *   <li>可重复迁移：从未执行或 checksum 已变化 → 加入 pending，排在所有版本化迁移之后。</li>
 *   <li>UNDONE：某版本最新记录为 UNDO_SQL 且本地 V 文件仍在 → 重新视为 pending。</li>
 * </ol>
 *
 * <p>「当前已应用最高版本」只统计 success=true、非 UNDO、非 BASELINE 的版本化记录（修复旧原型缺陷 #3）。
 */
public final class PendingCalculator {

    private PendingCalculator() {
    }

    /**
     * @param resolved   本地解析的迁移（须已排序：版本化升序 → 可重复按描述升序）
     * @param applied    历史表全部记录（任意顺序）
     * @param outOfOrder 是否允许乱序执行
     * @return 待执行列表（保持 resolved 的相对顺序）
     */
    public static List<ResolvedMigration> compute(List<ResolvedMigration> resolved,
                                                  List<AppliedMigration> applied,
                                                  boolean outOfOrder) {
        // 1) FAILED 阻断
        for (AppliedMigration record : applied) {
            if (!record.success()) {
                throw new FlydbException(ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR,
                        "失败脚本: " + record.script()
                                + "（修正脚本后执行 repair 清除失败记录，再重跑 migrate）");
            }
        }

        // 2) 索引：版本化记录按版本取最新（installedRank 最大）；可重复按 script 取最新；
        //    baseline 过滤版本取自历史表中的 BASELINE 记录（无则不过滤）。
        Map<MigrationVersion, AppliedMigration> latestByVersion = new HashMap<MigrationVersion, AppliedMigration>();
        Map<String, AppliedMigration> latestRepeatableByScript = new HashMap<String, AppliedMigration>();
        MigrationVersion baselineFilterVersion = null;
        for (AppliedMigration record : applied) {
            if (record.type() == MigrationType.BASELINE) {
                if (baselineFilterVersion == null
                        || record.version().compareTo(baselineFilterVersion) > 0) {
                    baselineFilterVersion = record.version();
                }
                continue; // baseline 不作为「已应用迁移」参与版本化/可重复索引
            }
            if (record.version() == null) {
                accumulateRepeatable(latestRepeatableByScript, record);
            } else {
                accumulateVersioned(latestByVersion, record);
            }
        }

        // 3) 当前已应用最高版本（仅 success、非 UNDO 的版本化记录）
        MigrationVersion latestSuccessfulVersion = null;
        for (Map.Entry<MigrationVersion, AppliedMigration> entry : latestByVersion.entrySet()) {
            AppliedMigration rec = entry.getValue();
            if (rec.type() == MigrationType.UNDO_SQL) {
                continue;
            }
            MigrationVersion ver = entry.getKey();
            if (latestSuccessfulVersion == null || ver.compareTo(latestSuccessfulVersion) > 0) {
                latestSuccessfulVersion = ver;
            }
        }

        // 4) 逐条裁定
        List<ResolvedMigration> pending = new ArrayList<ResolvedMigration>();
        for (ResolvedMigration migration : resolved) {
            if (migration.type() == MigrationType.UNDO_SQL) {
                continue;
            }
            if (migration.version() != null) {
                if (isVersionedPending(migration, latestByVersion, latestSuccessfulVersion,
                        baselineFilterVersion, outOfOrder)) {
                    pending.add(migration);
                }
            } else if (isRepeatablePending(migration, latestRepeatableByScript)) {
                pending.add(migration);
            }
        }
        return pending;
    }

    private static void accumulateVersioned(Map<MigrationVersion, AppliedMigration> map,
                                            AppliedMigration record) {
        AppliedMigration prev = map.get(record.version());
        if (prev == null || record.installedRank() > prev.installedRank()) {
            map.put(record.version(), record);
        }
    }

    private static void accumulateRepeatable(Map<String, AppliedMigration> map,
                                             AppliedMigration record) {
        AppliedMigration prev = map.get(record.script());
        if (prev == null || record.installedRank() > prev.installedRank()) {
            map.put(record.script(), record);
        }
    }

    private static boolean isVersionedPending(ResolvedMigration migration,
                                              Map<MigrationVersion, AppliedMigration> latestByVersion,
                                              MigrationVersion latestSuccessfulVersion,
                                              MigrationVersion baselineVersion,
                                              boolean outOfOrder) {
        MigrationVersion version = migration.version();
        // baseline 过滤
        if (baselineVersion != null && version.compareTo(baselineVersion) <= 0) {
            return false;
        }
        AppliedMigration latest = latestByVersion.get(version);
        // 已应用（最新记录非 UNDO）→ 不重复执行
        if (latest != null && latest.type() != MigrationType.UNDO_SQL) {
            return false;
        }
        // 未应用：与已应用最高版本比较
        boolean higher = latestSuccessfulVersion == null
                || version.compareTo(latestSuccessfulVersion) > 0;
        if (higher) {
            return true;
        }
        // 乱序
        if (!outOfOrder) {
            throw new FlydbException(ErrorCode.OUT_OF_ORDER_MIGRATION,
                    "迁移 " + migration.script() + "（版本 " + version + "）低于已应用最高版本 "
                            + latestSuccessfulVersion + "，设置 flydb.out-of-order=true 可补执行");
        }
        return true;
    }

    private static boolean isRepeatablePending(ResolvedMigration migration,
                                               Map<String, AppliedMigration> latestByScript) {
        AppliedMigration latest = latestByScript.get(migration.script());
        if (latest == null) {
            return true; // 从未执行
        }
        Integer local = migration.checksum();
        Integer recorded = latest.checksum();
        if (local == null) {
            return false; // 无 checksum 的 Java 可重复迁移视为不变
        }
        return !local.equals(recorded); // checksum 变化 → 待重跑
    }
}
