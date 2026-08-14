package com.flydb.core.command;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.ValidationProblem;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MigrationValidator")
class MigrationValidatorTest {

    @Test
    @DisplayName("一次收集 checksum、MISSING、FAILED、FUTURE 全部问题")
    void collectsAllValidationProblems() {
        ResolvedMigration local = resolved("1", 20);
        AppliedMigration changed = applied(1, "1", 10, true);
        MigrationInfo checksum = MigrationInfo.derive(local, changed,
                version("3"), version("1"));
        MigrationInfo missing = MigrationInfo.derive(null, applied(2, "1", 10, true),
                version("3"), version("3"));
        MigrationInfo failed = MigrationInfo.derive(null, applied(3, "2", 10, false),
                version("3"), version("3"));
        MigrationInfo future = MigrationInfo.derive(null, applied(4, "4", 10, true),
                version("4"), version("3"));

        List<ValidationProblem> problems = MigrationValidator.validate(
                Arrays.asList(checksum, missing, failed, future));

        assertThat(problems).extracting(ValidationProblem::errorCode)
                .containsExactly(ErrorCode.CHECKSUM_MISMATCH,
                        ErrorCode.CHECKSUM_MISMATCH,
                        ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR,
                        ErrorCode.CHECKSUM_MISMATCH);
        assertThat(problems).allSatisfy(problem -> assertThat(problem.detail()).isNotBlank());
    }

    private static MigrationVersion version(String value) {
        return MigrationVersion.parse(value);
    }

    private static ResolvedMigration resolved(String value, int checksum) {
        return ResolvedMigration.of(version(value), "x", "V" + value + "__x.sql",
                checksum, MigrationType.SQL);
    }

    private static AppliedMigration applied(int rank, String value, int checksum, boolean success) {
        return AppliedMigration.of(rank, version(value), "x", MigrationType.SQL,
                "V" + value + "__x.sql", checksum, "u", new Timestamp(0), 1, success);
    }
}
