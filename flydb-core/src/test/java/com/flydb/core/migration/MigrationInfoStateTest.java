package com.flydb.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

/**
 * MigrationInfo.derive 状态推导真值表逐行覆盖（设计 02 §6、08 §1）。
 *
 * <p>derive 是 info/validate/migrate 三个命令共用的纯函数：据「本地解析 + 历史记录 + 两个聚合版本」
 * 推导出 9 种状态之一。聚合版本由调用方（阶段 3 的 InfoCommand）按规则计算：
 * <ul>
 *   <li>latestSuccessfulVersion：仅统计 success=true、非 UNDO、版本化（含 BASELINE 语义由调用方裁定）记录的最大版本。</li>
 *   <li>latestResolvedVersion：本地版本化解析迁移的最大版本。</li>
 * </ul>
 */
class MigrationInfoStateTest {

    // ---------- PENDING / OUT_OF_ORDER ----------

    @Test
    void pending_when_version_higher_than_latest_success() {
        ResolvedMigration r = resolvedVersioned("3", 10);

        MigrationInfo info = MigrationInfo.derive(r, null, v("2"), v("3"));

        assertThat(info.state()).isEqualTo(MigrationState.PENDING);
    }

    @Test
    void out_of_order_when_version_lower_than_latest_success() {
        ResolvedMigration r = resolvedVersioned("1", 10);

        MigrationInfo info = MigrationInfo.derive(r, null, v("3"), v("3"));

        assertThat(info.state()).isEqualTo(MigrationState.OUT_OF_ORDER);
    }

    @Test
    void pending_when_nothing_applied_yet() {
        ResolvedMigration r = resolvedVersioned("1", 10);

        // 尚无任何成功记录 → 不可能 out-of-order
        MigrationInfo info = MigrationInfo.derive(r, null, null, v("1"));

        assertThat(info.state()).isEqualTo(MigrationState.PENDING);
    }

    @Test
    void pending_repeatable_when_never_applied() {
        ResolvedMigration r = resolvedRepeatable(10);

        MigrationInfo info = MigrationInfo.derive(r, null, v("5"), v("5"));

        assertThat(info.state()).isEqualTo(MigrationState.PENDING);
    }

    // ---------- SUCCESS / OUTDATED ----------

    @Test
    void success_versioned_when_checksum_matches() {
        ResolvedMigration r = resolvedVersioned("1", 10);
        AppliedMigration a = appliedVersioned("1", 10, true);

        assertThat(MigrationInfo.derive(r, a, v("1"), v("1")).state()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void success_repeatable_when_checksum_matches() {
        ResolvedMigration r = resolvedRepeatable(10);
        AppliedMigration a = appliedRepeatable(10, true);

        assertThat(MigrationInfo.derive(r, a, null, null).state()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void outdated_repeatable_when_checksum_differs() {
        ResolvedMigration r = resolvedRepeatable(11);
        AppliedMigration a = appliedRepeatable(10, true);

        // 可重复迁移 checksum 变化 → 待重跑（OUTDATED）
        assertThat(MigrationInfo.derive(r, a, null, null).state()).isEqualTo(MigrationState.OUTDATED);
    }

    @Test
    void versioned_checksum_drift_is_success_from_derive_validate_flags_separately() {
        // 版本化迁移 checksum 漂移：迁移确已成功应用（不可重跑），故 derive 返回 SUCCESS；
        // checksum 是否一致由 validate 命令另行检测并收集 FLYDB-2003（设计 02 §9、05 §3）。
        ResolvedMigration r = resolvedVersioned("1", 11);
        AppliedMigration a = appliedVersioned("1", 10, true);

        assertThat(MigrationInfo.derive(r, a, v("1"), v("1")).state()).isEqualTo(MigrationState.SUCCESS);
    }

    // ---------- FAILED ----------

    @Test
    void failed_when_applied_unsuccessful() {
        AppliedMigration a = appliedVersioned("1", 10, false);
        ResolvedMigration r = resolvedVersioned("1", 10);

        assertThat(MigrationInfo.derive(r, a, null, v("1")).state()).isEqualTo(MigrationState.FAILED);
    }

    @Test
    void failed_when_applied_unsuccessful_even_if_local_gone() {
        AppliedMigration a = appliedVersioned("1", 10, false);

        assertThat(MigrationInfo.derive(null, a, null, v("1")).state()).isEqualTo(MigrationState.FAILED);
    }

    // ---------- MISSING / FUTURE ----------

    @Test
    void missing_when_applied_without_local_and_version_le_local_max() {
        AppliedMigration a = appliedVersioned("2", 10, true);

        // 本地最高版本 5 ≥ 库里记录版本 2，但本地已无该脚本 → 缺失
        assertThat(MigrationInfo.derive(null, a, v("5"), v("5")).state()).isEqualTo(MigrationState.MISSING);
    }

    @Test
    void future_when_applied_without_local_and_version_gt_local_max() {
        AppliedMigration a = appliedVersioned("10", 10, true);

        // 库里记录版本 10 高于本地最高版本 5（代码回滚、库没回）→ 未来版本
        assertThat(MigrationInfo.derive(null, a, v("10"), v("5")).state()).isEqualTo(MigrationState.FUTURE);
    }

    // ---------- BASELINE / UNDONE ----------

    @Test
    void baseline_when_applied_type_is_baseline() {
        AppliedMigration a = AppliedMigration.of(1, v("5"), "baseline", MigrationType.BASELINE,
                "<< BASELINE >>", null, "root", new Timestamp(0), 0, true);

        assertThat(MigrationInfo.derive(null, a, v("5"), null).state()).isEqualTo(MigrationState.BASELINE);
    }

    @Test
    void undone_when_latest_record_is_undo_sql() {
        AppliedMigration a = AppliedMigration.of(2, v("1"), "undo", MigrationType.UNDO_SQL,
                "U1__undo.sql", null, "root", new Timestamp(0), 0, true);

        assertThat(MigrationInfo.derive(null, a, null, v("1")).state()).isEqualTo(MigrationState.UNDONE);
    }

    // ---------- 顺带覆盖：compareTo 与访问器 ----------

    @Test
    void exposes_resolved_applied_and_state() {
        ResolvedMigration r = resolvedVersioned("1", 10);
        AppliedMigration a = appliedVersioned("1", 10, true);
        MigrationInfo info = MigrationInfo.derive(r, a, v("1"), v("1"));

        assertThat(info.resolved()).isSameAs(r);
        assertThat(info.applied()).isSameAs(a);
        assertThat(info.state()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void orders_by_version_with_repeatable_last() {
        MigrationInfo versioned = MigrationInfo.derive(resolvedVersioned("2", 1), null, null, v("2"));
        MigrationInfo repeatable = MigrationInfo.derive(resolvedRepeatable(1), null, null, v("2"));

        assertThat(versioned.compareTo(repeatable)).isNegative();
        assertThat(repeatable.compareTo(versioned)).isPositive();
    }

    // ---------- 测试夹具 ----------

    private static MigrationVersion v(String text) {
        return MigrationVersion.parse(text);
    }

    private static ResolvedMigration resolvedVersioned(String version, int checksum) {
        return ResolvedMigration.of(v(version), "desc", "V" + version + "__desc.sql", checksum, MigrationType.SQL);
    }

    private static ResolvedMigration resolvedRepeatable(int checksum) {
        return ResolvedMigration.of(null, "view", "R__view.sql", checksum, MigrationType.SQL);
    }

    private static AppliedMigration appliedVersioned(String version, int checksum, boolean success) {
        return AppliedMigration.of(1, v(version), "desc", MigrationType.SQL,
                "V" + version + "__desc.sql", checksum, "u", new Timestamp(0), 10, success);
    }

    private static AppliedMigration appliedRepeatable(int checksum, boolean success) {
        return AppliedMigration.of(1, null, "view", MigrationType.SQL,
                "R__view.sql", checksum, "u", new Timestamp(0), 10, success);
    }
}
