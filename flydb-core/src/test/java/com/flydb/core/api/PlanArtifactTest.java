package com.flydb.core.api;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlanArtifact v1 确定性摘要")
class PlanArtifactTest {

    private static DryRunMigration migration(String version, String script, Integer checksum,
                                             int statementCount) {
        return new DryRunMigration(script, MigrationType.SQL,
                version == null ? null : MigrationVersion.parse(version),
                "desc", checksum, statements(statementCount));
    }

    private static java.util.List<DryRunStatement> statements(int count) {
        java.util.List<DryRunStatement> result = new java.util.ArrayList<DryRunStatement>();
        for (int i = 0; i < count; i++) result.add(new DryRunStatement(i + 1, "SELECT " + i));
        return result;
    }

    @Test
    @DisplayName("同一计划必然得到同一 id，字段或顺序变化得到不同 id")
    void idIsDeterministicAndSensitive() {
        DryRunResult first = new DryRunResult("migrate", Arrays.asList(
                migration("1", "V1__init.sql", 10, 1),
                migration("2", "V2__add.sql", 20, 2)));
        DryRunResult same = new DryRunResult("migrate", Arrays.asList(
                migration("1", "V1__init.sql", 10, 1),
                migration("2", "V2__add.sql", 20, 2)));

        assertThat(PlanArtifact.of(first).id()).isEqualTo(PlanArtifact.of(same).id())
                .hasSize(64).matches("[0-9a-f]{64}");

        DryRunResult reordered = new DryRunResult("migrate", Arrays.asList(
                migration("2", "V2__add.sql", 20, 2),
                migration("1", "V1__init.sql", 10, 1)));
        DryRunResult differentChecksum = new DryRunResult("migrate", Arrays.asList(
                migration("1", "V1__init.sql", 10, 1),
                migration("2", "V2__add.sql", 21, 2)));
        DryRunResult undoDirection = new DryRunResult("undo", Arrays.asList(
                migration("1", "V1__init.sql", 10, 1),
                migration("2", "V2__add.sql", 20, 2)));

        assertThat(PlanArtifact.of(reordered).id()).isNotEqualTo(PlanArtifact.of(first).id());
        assertThat(PlanArtifact.of(differentChecksum).id()).isNotEqualTo(PlanArtifact.of(first).id());
        assertThat(PlanArtifact.of(undoDirection).id()).isNotEqualTo(PlanArtifact.of(first).id());
    }

    @Test
    @DisplayName("规范文本与摘要的已知向量固定不变")
    void canonicalTextVectorIsPinned() {
        DryRunResult result = new DryRunResult("migrate", Collections.singletonList(
                new DryRunMigration("V2__add_order.sql", MigrationType.SQL,
                        MigrationVersion.parse("2"), "add_order", 777, statements(2))));

        assertThat(PlanArtifact.canonicalText(result))
                .isEqualTo("flydb-plan-v1\nmigrate\n2\tSQL\tV2__add_order.sql\t777\t2\n");
        assertThat(PlanArtifact.of(result).id())
                .isEqualTo("ba1b0bc7a857b8a06131d8c031ec8a2d2f5b6df20e32eaaf1496bdf77b23000a");
    }

    @Test
    @DisplayName("null 版本与 null checksum 在规范文本中为空串，目标版本取最后一个版本化迁移")
    void nullFieldsAreEmptyInCanonicalText() {
        DryRunResult result = new DryRunResult("undo", Collections.singletonList(
                new DryRunMigration("U2__drop_order.sql", MigrationType.UNDO_SQL,
                        null, "drop_order", 88, statements(1))));

        assertThat(PlanArtifact.canonicalText(result))
                .isEqualTo("flydb-plan-v1\nundo\n\tUNDO_SQL\tU2__drop_order.sql\t88\t1\n");
        assertThat(PlanArtifact.of(result).targetVersion()).isNull();
        assertThat(PlanArtifact.of(result).id())
                .isEqualTo("dd702401a06e9a68d200da64c8d7d0b8856299a468aa772d5f40f1c7e200f73a");
    }

    @Test
    @DisplayName("摘要字段：计数、语句数与混合版本/可重复计划的目标版本")
    void summaryFields() {
        DryRunResult result = new DryRunResult("migrate", Arrays.asList(
                migration("1", "V1__init.sql", 10, 2),
                new DryRunMigration("R__views.sql", MigrationType.SQL, null,
                        "views", 30, statements(3)),
                migration("3", "V3__index.sql", 40, 1)));

        PlanArtifact plan = PlanArtifact.of(result);
        assertThat(plan.direction()).isEqualTo("migrate");
        assertThat(plan.migrationCount()).isEqualTo(3);
        assertThat(plan.statementCount()).isEqualTo(6);
        assertThat(plan.targetVersion()).isEqualTo("3");
    }

    @Test
    @DisplayName("空计划与非法方向")
    void emptyPlanAndInvalidDirection() {
        PlanArtifact empty = PlanArtifact.of(new DryRunResult("migrate",
                Collections.<DryRunMigration>emptyList()));
        assertThat(empty.migrationCount()).isZero();
        assertThat(empty.statementCount()).isZero();
        assertThat(empty.targetVersion()).isNull();
        assertThat(empty.id()).hasSize(64);

        assertThatThrownBy(() -> new DryRunResult("apply", Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
