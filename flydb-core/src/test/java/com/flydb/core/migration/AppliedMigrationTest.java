package com.flydb.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

/**
 * AppliedMigration / ResolvedMigration 不可变性与工厂测试（设计 00 §6、02 §4）。
 *
 * <p>{@link AppliedMigration#installedOn()} 返回 {@link Timestamp}（可变），必须防御性拷贝：
 * 构造后改动入参与改动返回值都不应影响对象内部状态。
 */
class AppliedMigrationTest {

    private final MigrationVersion version = MigrationVersion.parse("1");

    @Test
    void factory_populates_all_fields() {
        Timestamp on = new Timestamp(1_700_000_000_000L);
        AppliedMigration am = AppliedMigration.of(3, version, "add col", MigrationType.SQL,
                "V1__add_col.sql", 42, "root", on, 250, true);

        assertThat(am.installedRank()).isEqualTo(3);
        assertThat(am.version()).isEqualTo(version);
        assertThat(am.description()).isEqualTo("add col");
        assertThat(am.type()).isEqualTo(MigrationType.SQL);
        assertThat(am.script()).isEqualTo("V1__add_col.sql");
        assertThat(am.checksum()).isEqualTo(42);
        assertThat(am.installedBy()).isEqualTo("root");
        assertThat(am.installedOn()).isEqualTo(on);
        assertThat(am.executionTimeMillis()).isEqualTo(250);
        assertThat(am.success()).isTrue();
    }

    @Test
    void installed_on_defensively_copied_on_construction() {
        Timestamp input = new Timestamp(1_000L);
        AppliedMigration am = AppliedMigration.of(1, version, "d", MigrationType.SQL, "s", 1, "u", input, 1, true);

        input.setTime(9_999L); // 改动构造入参

        assertThat(am.installedOn().getTime()).isEqualTo(1_000L);
    }

    @Test
    void installed_on_returned_is_defensive_copy() {
        AppliedMigration am = AppliedMigration.of(1, version, "d", MigrationType.SQL, "s", 1, "u",
                new Timestamp(1_000L), 1, true);

        am.installedOn().setTime(9_999L); // 改动返回值

        assertThat(am.installedOn().getTime()).isEqualTo(1_000L);
    }

    @Test
    void resolved_migration_factory_populates_fields() {
        ResolvedMigration rm = ResolvedMigration.of(version, "init", "V1__init.sql", 7, MigrationType.SQL);

        assertThat(rm.version()).isEqualTo(version);
        assertThat(rm.description()).isEqualTo("init");
        assertThat(rm.script()).isEqualTo("V1__init.sql");
        assertThat(rm.checksum()).isEqualTo(7);
        assertThat(rm.type()).isEqualTo(MigrationType.SQL);
    }

    @Test
    void resolved_migration_allows_null_version_and_checksum() {
        // 可重复迁移 version=null；Java 迁移 checksum=null
        ResolvedMigration repeatable = ResolvedMigration.of(null, "view", "R__view.sql", 9, MigrationType.SQL);

        assertThat(repeatable.version()).isNull();
        assertThat(repeatable.checksum()).isEqualTo(9);
    }
}
