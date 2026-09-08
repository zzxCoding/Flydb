package com.flydb.core.api;

import java.sql.Timestamp;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MigrationInfoService")
class MigrationInfoServiceTest {

    @Test void currentIncludesSuccessfulHistoryMissingFromTheLocalCheckout() {
        MigrationInfo future = MigrationInfo.derive(null, applied("20260901.3"), version("20260901.3"), version("20260227.3"));
        MigrationInfo older = MigrationInfo.derive(resolved("20260101.1"), null, version("20260901.3"), version("20260227.3"));
        MigrationInfoService service = new MigrationInfoService(Arrays.asList(older, future));
        assertThat(service.current()).isEqualTo(version("20260901.3"));
        assertThat(service.pending()).containsExactly(older);
        MigrationInfo missing = MigrationInfo.derive(null, applied("2"), version("2"), version("3"));
        assertThat(new MigrationInfoService(Arrays.asList(missing)).current()).isEqualTo(version("2"));
    }

    @Test void currentExcludesFailedAndUndoneHistory() {
        AppliedMigration failed = AppliedMigration.of(2, version("4"), "failed", MigrationType.SQL,
                "V4__failed.sql", 1, "u", new Timestamp(0), 1, false);
        AppliedMigration undone = AppliedMigration.of(3, version("5"), "undone", MigrationType.UNDO_SQL,
                "U5__undone.sql", 1, "u", new Timestamp(0), 1, true);
        MigrationInfoService service = new MigrationInfoService(Arrays.asList(
                MigrationInfo.derive(null, applied("2"), version("2"), version("1")),
                MigrationInfo.derive(null, failed, version("2"), version("1")),
                MigrationInfo.derive(null, undone, version("2"), version("1"))));
        assertThat(service.current()).isEqualTo(version("2"));
    }


    @Test
    @DisplayName("提供 pending/applied/current 只读视图")
    void exposesFilteredViewsAndCurrentVersion() {
        ResolvedMigration pendingResolved = resolved("3");
        MigrationInfo pending = MigrationInfo.derive(pendingResolved, null, version("2"), version("3"));
        ResolvedMigration appliedResolved = resolved("2");
        AppliedMigration appliedRecord = applied("2");
        MigrationInfo applied = MigrationInfo.derive(appliedResolved, appliedRecord,
                version("2"), version("3"));
        MigrationInfoService service = new MigrationInfoService(Arrays.asList(pending, applied));

        assertThat(service.pending()).containsExactly(pending);
        assertThat(service.applied()).containsExactly(applied);
        assertThat(service.current()).isEqualTo(version("2"));
    }

    private static MigrationVersion version(String value) {
        return MigrationVersion.parse(value);
    }

    private static ResolvedMigration resolved(String value) {
        return ResolvedMigration.of(version(value), "v" + value,
                "V" + value + "__x.sql", 1, MigrationType.SQL);
    }

    private static AppliedMigration applied(String value) {
        return AppliedMigration.of(1, version(value), "v" + value, MigrationType.SQL,
                "V" + value + "__x.sql", 1, "u", new Timestamp(0), 1, true);
    }
}
