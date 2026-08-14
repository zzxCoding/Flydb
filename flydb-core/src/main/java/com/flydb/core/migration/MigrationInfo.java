package com.flydb.core.migration;

/**
 * 单条迁移的视图：本地解析 + 历史记录 + 推导出的状态（设计 02 §6）。
 *
 * <p>{@link #derive(ResolvedMigration, AppliedMigration, MigrationVersion, MigrationVersion)}
 * 是纯函数，info / validate / migrate 三个命令共用同一状态推导实现。不可变。
 *
 * <p>状态推导规则（真值表见设计 02 §6）：
 * <ul>
 *   <li>{@code applied.type=BASELINE} → {@link MigrationState#BASELINE}</li>
 *   <li>{@code applied.type=UNDO_SQL}（该版本最新记录为撤销）→ {@link MigrationState#UNDONE}</li>
 *   <li>{@code applied.success=false} → {@link MigrationState#FAILED}（阻塞 migrate，需 repair）</li>
 *   <li>resolved + applied(success)：可重复迁移 checksum 变化 → {@link MigrationState#OUTDATED}，否则 {@link MigrationState#SUCCESS}</li>
 *   <li>resolved + 无 applied：version &gt; latestSuccessfulVersion → {@link MigrationState#PENDING}，
 *       否则 {@link MigrationState#OUT_OF_ORDER}；可重复迁移一律 {@code PENDING}</li>
 *   <li>无 resolved + applied(success)：version &gt; latestResolvedVersion → {@link MigrationState#FUTURE}，
 *       否则 {@link MigrationState#MISSING}</li>
 * </ul>
 *
 * <p>注意：版本化迁移的 checksum 漂移不在此函数体现——derive 仍返回 {@code SUCCESS}（迁移确已成功、不可重跑），
 * checksum 是否一致由 validate 命令另行检测并收集 FLYDB-2003（设计 02 §9、05 §3）。
 *
 * <p>{@code latestSuccessfulVersion}：调用方按「success=true、非 UNDO、版本化」记录的最大版本计算
 * （修复旧原型缺陷 #3：失败/已撤销记录不计入「已应用最高版本」）。
 */
public final class MigrationInfo implements Comparable<MigrationInfo> {

    private final ResolvedMigration resolved;
    private final AppliedMigration applied;
    private final MigrationState state;

    private MigrationInfo(ResolvedMigration resolved, AppliedMigration applied, MigrationState state) {
        this.resolved = resolved;
        this.applied = applied;
        this.state = state;
    }

    /**
     * 纯函数状态推导。
     *
     * @param resolved               本地解析的迁移，可空（MISSING/FUTURE）
     * @param applied                历史表记录，可空（PENDING/OUT_OF_ORDER）
     * @param latestSuccessfulVersion 已成功应用的最大版本化版本（不含 FAILED/UNDO），无则 null
     * @param latestResolvedVersion  本地解析的最大版本化版本，无则 null
     * @return 推导出的 {@link MigrationInfo}
     */
    public static MigrationInfo derive(ResolvedMigration resolved, AppliedMigration applied,
                                       MigrationVersion latestSuccessfulVersion,
                                       MigrationVersion latestResolvedVersion) {
        return new MigrationInfo(resolved, applied,
                computeState(resolved, applied, latestSuccessfulVersion, latestResolvedVersion));
    }

    private static MigrationState computeState(ResolvedMigration resolved, AppliedMigration applied,
                                               MigrationVersion latestSuccessfulVersion,
                                               MigrationVersion latestResolvedVersion) {
        // 1) 仅凭 applied 即可判定的优先状态
        if (applied != null) {
            if (applied.type() == MigrationType.BASELINE) {
                return MigrationState.BASELINE;
            }
            if (applied.type() == MigrationType.UNDO_SQL) {
                return MigrationState.UNDONE;
            }
            if (!applied.success()) {
                return MigrationState.FAILED;
            }
        }

        // 2) resolved + applied(success) —— 已应用
        if (resolved != null && applied != null) {
            if (resolved.version() == null) {
                // 可重复迁移：checksum 变化 → OUTDATED（待重跑），否则 SUCCESS
                return checksumEquals(resolved, applied)
                        ? MigrationState.SUCCESS
                        : MigrationState.OUTDATED;
            }
            // 版本化迁移已成功应用 → SUCCESS（checksum 漂移由 validate 另行检测）
            return MigrationState.SUCCESS;
        }

        // 3) resolved + 无 applied —— 未应用
        if (resolved != null) {
            if (resolved.version() == null) {
                // 可重复迁移从未执行 → 待执行
                return MigrationState.PENDING;
            }
            if (latestSuccessfulVersion == null
                    || resolved.version().compareTo(latestSuccessfulVersion) > 0) {
                return MigrationState.PENDING;
            }
            return MigrationState.OUT_OF_ORDER;
        }

        // 4) 无 resolved + applied(success) —— 本地脚本已不在
        if (applied != null) {
            if (applied.version() == null
                    || latestResolvedVersion == null
                    || applied.version().compareTo(latestResolvedVersion) > 0) {
                return MigrationState.FUTURE;
            }
            return MigrationState.MISSING;
        }

        // resolved 与 applied 同时为 null：无推导素材，不应发生
        throw new IllegalArgumentException("MigrationInfo.derive 需至少提供 resolved 或 applied 之一");
    }

    private static boolean checksumEquals(ResolvedMigration resolved, AppliedMigration applied) {
        Integer local = resolved.checksum();
        Integer recorded = applied.checksum();
        if (local == null && recorded == null) {
            return true;
        }
        return local != null && local.equals(recorded);
    }

    public ResolvedMigration resolved() {
        return resolved;
    }

    public AppliedMigration applied() {
        return applied;
    }

    public MigrationState state() {
        return state;
    }

    /** 排序：按有效版本升序，可重复/无版本记录排在最后；同版本按描述。 */
    @Override
    public int compareTo(MigrationInfo other) {
        MigrationVersion a = effectiveVersion();
        MigrationVersion b = other.effectiveVersion();
        if (a == null && b == null) {
            return compareStrings(effectiveDescription(), other.effectiveDescription());
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        int byVersion = a.compareTo(b);
        return byVersion != 0 ? byVersion
                : compareStrings(effectiveDescription(), other.effectiveDescription());
    }

    private MigrationVersion effectiveVersion() {
        if (resolved != null && resolved.version() != null) {
            return resolved.version();
        }
        return applied != null ? applied.version() : null;
    }

    private String effectiveDescription() {
        if (resolved != null && resolved.description() != null) {
            return resolved.description();
        }
        return applied != null && applied.description() != null ? applied.description() : "";
    }

    private static int compareStrings(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        return a.compareTo(b);
    }
}
