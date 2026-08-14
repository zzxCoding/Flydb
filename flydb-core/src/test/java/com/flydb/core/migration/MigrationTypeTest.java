package com.flydb.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** MigrationType 常量集契约（设计 02 §4）——码值稳定，勿删/勿改顺序。 */
class MigrationTypeTest {

    @Test
    void constants_match_design_in_declaration_order() {
        assertThat(EnumSet.allOf(MigrationType.class))
                .containsExactly(MigrationType.SQL, MigrationType.JDBC,
                        MigrationType.BASELINE, MigrationType.UNDO_SQL);
    }
}
