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
