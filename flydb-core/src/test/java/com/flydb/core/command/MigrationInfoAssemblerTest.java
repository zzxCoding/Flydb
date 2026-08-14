package com.flydb.core.command;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationState;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MigrationInfoAssembler")
class MigrationInfoAssemblerTest {

    @Test
    @DisplayName("对本地与历史做全外连接并以最新 installed_rank 为准")
    void fullOuterJoinUsesLatestHistoryRecord() {
        ResolvedMigration v1 = resolved("1", MigrationType.SQL, 11);
        ResolvedMigration v2 = resolved("2", MigrationType.SQL, 22);
        ResolvedMigration u2 = resolved("2", MigrationType.UNDO_SQL, 33);
        AppliedMigration old = applied(1, "1", MigrationType.SQL, 11, true);
        AppliedMigration undone = applied(2, "1", MigrationType.UNDO_SQL, 44, true);

        List<MigrationInfo> infos = MigrationInfoAssembler.assemble(
                Arrays.asList(v1, v2, u2), Arrays.asList(old, undone));

        assertThat(infos).hasSize(2);
        assertThat(infos).extracting(MigrationInfo::state)
                .containsExactly(MigrationState.UNDONE, MigrationState.PENDING);
        assertThat(infos).noneMatch(info -> info.resolved() != null
                && info.resolved().type() == MigrationType.UNDO_SQL);
    }

    private static ResolvedMigration resolved(String version, MigrationType type, int checksum) {
        String prefix = type == MigrationType.UNDO_SQL ? "U" : "V";
        return ResolvedMigration.of(MigrationVersion.parse(version), "x",
                prefix + version + "__x.sql", checksum, type);
    }

    private static AppliedMigration applied(int rank, String version, MigrationType type,
                                             int checksum, boolean success) {
        return AppliedMigration.of(rank, MigrationVersion.parse(version), "x", type,
                (type == MigrationType.UNDO_SQL ? "U" : "V") + version + "__x.sql",
                checksum, "u", new Timestamp(0), 1, success);
    }
}
