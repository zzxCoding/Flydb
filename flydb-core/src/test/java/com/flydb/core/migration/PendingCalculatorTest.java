package com.flydb.core.migration;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PendingCalculator 单测（设计 05 §1.1、08 §1）。
 *
 * <p>PendingCalculator 是 migrate 决策的纯函数：给定 resolved（已排序）+ applied，算出待执行列表。
 * 覆盖：FAILED 阻断（FLYDB-2004）、baseline 过滤、outOfOrder 两态（FLYDB-2006）、
 * 可重复迁移排序、UNDONE 重入。
 */
@DisplayName("PendingCalculator")
class PendingCalculatorTest {

    private static final Timestamp NOW = new Timestamp(1700000000000L);

    private static MigrationVersion v(String text) {
        return MigrationVersion.parse(text);
    }

    private static ResolvedMigration sql(MigrationVersion version, String desc, String script, int checksum) {
        return ResolvedMigration.of(version, desc, script, checksum, MigrationType.SQL);
    }

    private static ResolvedMigration directorySql(String version, String directoryVersion,
                                                  String script, int checksum) {
        return ResolvedMigration.of(v(version), v(directoryVersion), script, script,
                checksum, MigrationType.SQL);
    }

    /** 版本化 SQL 已成功应用记录。 */
    private static AppliedMigration applied(int rank, String version, String script, int checksum) {
        return AppliedMigration.of(rank, version == null ? null : v(version), script.split("__", 2)[0],
                MigrationType.SQL, script, checksum, "root", NOW, 10, true);
    }

    private static AppliedMigration baseline(int rank, String version) {
        return AppliedMigration.of(rank, v(version), "<< Baseline >>", MigrationType.BASELINE,
                "<< Baseline >>", null, "root", NOW, 0, true);
    }

    private static AppliedMigration failed(int rank, String version, String script) {
        return AppliedMigration.of(rank, v(version), script.split("__", 2)[0], MigrationType.SQL,
                script, 999, "root", NOW, 5, false);
    }

    private static AppliedMigration undone(int rank, String version, String script) {
        return AppliedMigration.of(rank, v(version), script.split("__", 2)[0], MigrationType.UNDO_SQL,
                script, 999, "root", NOW, 8, true);
    }

    @Nested
    @DisplayName("FAILED 阻断")
    class FailedBlocks {

        @Test
        @DisplayName("存在 success=false 记录 → FLYDB-2004，即使有更高版本待执行")
        void failedRecordBlocksMigrate() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("2"), "two", "V2__two.sql", 20));
            java.util.List<AppliedMigration> applied = Arrays.asList(
                    failed(1, "1", "V1__one.sql"));

            assertThatThrownBy(() -> PendingCalculator.compute(
                    resolved, applied, false))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> assertThat(((FlydbException) ex).errorCode())
                            .isEqualTo(ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR));
        }
    }

    @Nested
    @DisplayName("baseline 过滤")
    class BaselineFilter {

        @Test
        @DisplayName("版本 <= baselineVersion 的 resolved 被跳过")
        void belowOrEqualBaselineSkipped() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("1"), "one", "V1__one.sql", 10),
                    sql(v("2"), "two", "V2__two.sql", 20),
                    sql(v("3"), "three", "V3__three.sql", 30));
            // baseline 在 2，V1/V2 应被过滤
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, Collections.<AppliedMigration>singletonList(baseline(1, "2")),
                    false);
            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("V3__three.sql");
        }

        @Test
        @DisplayName("空 resolved 与空 applied 返回空 pending")
        void emptyInputReturnsEmpty() {
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    Collections.<ResolvedMigration>emptyList(),
                    Collections.<AppliedMigration>emptyList(),
                    false);
            assertThat(pending).isEmpty();
        }
    }

    @Nested
    @DisplayName("outOfOrder 两态")
    class OutOfOrder {

        @Test
        @DisplayName("outOfOrder=false：低于已应用最高版本的未执行迁移 → FLYDB-2006")
        void outOfOrderForbiddenErrors() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("1"), "one", "V1__one.sql", 10),
                    sql(v("3"), "three", "V3__three.sql", 30));
            // V3 已应用，V1 未应用 → 乱序
            java.util.List<AppliedMigration> applied = Collections.singletonList(
                    applied(1, "3", "V3__three.sql", 30));
            assertThatThrownBy(() -> PendingCalculator.compute(
                    resolved, applied, false))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> assertThat(((FlydbException) ex).errorCode())
                            .isEqualTo(ErrorCode.OUT_OF_ORDER_MIGRATION));
        }

        @Test
        @DisplayName("outOfOrder=true：乱序迁移按版本序加入 pending")
        void outOfOrderAllowed() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("1"), "one", "V1__one.sql", 10),
                    sql(v("3"), "three", "V3__three.sql", 30));
            java.util.List<AppliedMigration> applied = Collections.singletonList(
                    applied(1, "3", "V3__three.sql", 30));
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, applied, true);
            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("V1__one.sql");
        }

        @Test
        @DisplayName("高于已应用最高版本的未执行迁移 → PENDING（无论 outOfOrder）")
        void higherVersionAlwaysPending() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("2"), "two", "V2__two.sql", 20),
                    sql(v("3"), "three", "V3__three.sql", 30));
            java.util.List<AppliedMigration> applied = Collections.singletonList(
                    applied(1, "1", "V1__one.sql", 10));
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, applied, false);
            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("V2__two.sql", "V3__three.sql");
        }

        @Test
        @DisplayName("已成功应用的版本不重复执行")
        void appliedVersionNotReExecuted() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("1"), "one", "V1__one.sql", 10),
                    sql(v("2"), "two", "V2__two.sql", 20));
            java.util.List<AppliedMigration> applied = Arrays.asList(
                    applied(1, "1", "V1__one.sql", 10),
                    applied(2, "2", "V2__two.sql", 20));
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, applied, false);
            assertThat(pending).isEmpty();
        }
    }

    @Nested
    @DisplayName("可重复迁移")
    class Repeatable {

        private ResolvedMigration repeatable(String desc, String script, int checksum) {
            return ResolvedMigration.of(null, desc, script, checksum, MigrationType.SQL);
        }

        @Test
        @DisplayName("从未执行的可重复迁移 → pending，排在所有版本化迁移之后")
        void neverAppliedRepeatablePending() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("1"), "one", "V1__one.sql", 10),
                    repeatable("aaa", "R__aaa.sql", 100),
                    repeatable("bbb", "R__bbb.sql", 200));
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, Collections.<AppliedMigration>emptyList(), false);
            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("V1__one.sql", "R__aaa.sql", "R__bbb.sql");
        }

        @Test
        @DisplayName("checksum 变化的可重复迁移 → pending（OUTDATED）")
        void changedChecksumRepeatablePending() {
            ResolvedMigration r = repeatable("views", "R__views.sql", 555);
            AppliedMigration lastRun = AppliedMigration.of(1, null, "views", MigrationType.SQL,
                    "R__views.sql", 111, "root", NOW, 9, true);
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    Collections.singletonList(r), Collections.singletonList(lastRun), false);
            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("R__views.sql");
        }

        @Test
        @DisplayName("checksum 未变的可重复迁移不重复执行")
        void unchangedChecksumRepeatableSkipped() {
            ResolvedMigration r = repeatable("views", "R__views.sql", 111);
            AppliedMigration lastRun = AppliedMigration.of(1, null, "views", MigrationType.SQL,
                    "R__views.sql", 111, "root", NOW, 9, true);
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    Collections.singletonList(r), Collections.singletonList(lastRun), false);
            assertThat(pending).isEmpty();
        }
    }

    @Nested
    @DisplayName("UNDONE 重入")
    class UndoneReentry {

        @Test
        @DisplayName("某版本最新记录为 UNDO 且本地 V 文件仍在 → 重新视为 pending")
        void undoneVersionRePends() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("1"), "one", "V1__one.sql", 10),
                    sql(v("2"), "two", "V2__two.sql", 20));
            // V2 先应用成功，再被撤销 → 最新记录为 UNDO_SQL
            java.util.List<AppliedMigration> applied = Arrays.asList(
                    applied(1, "2", "V2__two.sql", 20),
                    undone(2, "2", "V2__two.sql"));
            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, applied, false);
            // V2 被撤销后 latestSuccessfulVersion 仅来自成功非 UNDO 记录 → V2 > null 视为待执行
            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("V1__one.sql", "V2__two.sql");
        }
    }

    @Nested
    @DisplayName("版本筛选规则")
    class VersionSelectionRules {

        @Test
        @DisplayName("family 精确按数字段选择一个版本族，不把相似字符串当成子版本")
        void selectsACompleteVersionFamilyByNumericSegments() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("20230531.1"), "one", "V20230531.1__one.sql", 1),
                    sql(v("20230531.2"), "two", "V20230531.2__two.sql", 2),
                    sql(v("202305310.1"), "similar", "V202305310.1__similar.sql", 3),
                    sql(v("20230727.1"), "next", "V20230727.1__next.sql", 4));

            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, Collections.<AppliedMigration>emptyList(), false,
                    VersionSelection.family(v("20230531"), VersionSource.FILE));

            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("V20230531.1__one.sql", "V20230531.2__two.sql");
        }

        @Test
        @DisplayName("family-range 包含结束版本族的所有子版本")
        void familyRangeIncludesDescendantsOfTheEndBoundary() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("20230531.1"), "first", "V20230531.1__first.sql", 1),
                    sql(v("20230727.1"), "end-one", "V20230727.1__end_one.sql", 2),
                    sql(v("20230727.9"), "end-nine", "V20230727.9__end_nine.sql", 3),
                    sql(v("20230801.1"), "later", "V20230801.1__later.sql", 4));

            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, Collections.<AppliedMigration>emptyList(), false,
                    VersionSelection.familyRange(v("20230531"), v("20230727"),
                            VersionSource.FILE));

            assertThat(pending).extracting(ResolvedMigration::script).containsExactly(
                    "V20230531.1__first.sql", "V20230727.1__end_one.sql",
                    "V20230727.9__end_nine.sql");
        }

        @Test
        @DisplayName("exact 可以改用目录版本并一次选择目录内多个文件版本")
        void exactSelectionCanUseDirectoryVersionAsItsCoordinate() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    directorySql("20230531.1", "20230531", "20230531/V20230531.1__one.sql", 1),
                    directorySql("20230531.2", "20230531", "20230531/V20230531.2__two.sql", 2),
                    directorySql("20230727.1", "20230727", "20230727/V20230727.1__next.sql", 3));

            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, Collections.<AppliedMigration>emptyList(), false,
                    VersionSelection.exact(v("20230531"), VersionSource.DIRECTORY));

            assertThat(pending).extracting(ResolvedMigration::script).containsExactly(
                    "20230531/V20230531.1__one.sql",
                    "20230531/V20230531.2__two.sql");
        }

        @Test
        @DisplayName("regex 对规范化版本文本整串匹配且无命中时报错")
        void regexUsesFullVersionMatchingAndRejectsNoMatch() {
            java.util.List<ResolvedMigration> resolved = Arrays.asList(
                    sql(v("20230531.1"), "one", "V20230531.1__one.sql", 1),
                    sql(v("20230727.1"), "two", "V20230727.1__two.sql", 2));

            java.util.List<ResolvedMigration> pending = PendingCalculator.compute(
                    resolved, Collections.<AppliedMigration>emptyList(), false,
                    VersionSelection.regex("^20230531\\.\\d+$", VersionSource.FILE));
            assertThat(pending).extracting(ResolvedMigration::script)
                    .containsExactly("V20230531.1__one.sql");

            assertThatThrownBy(() -> PendingCalculator.compute(
                    resolved, Collections.<AppliedMigration>emptyList(), false,
                    VersionSelection.regex("^1999.*$", VersionSource.FILE)))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(error -> assertThat(((FlydbException) error).errorCode())
                            .isEqualTo(ErrorCode.INVALID_VERSION));
        }
    }
}
