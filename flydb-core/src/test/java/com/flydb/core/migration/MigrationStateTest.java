package com.flydb.core.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** MigrationState 常量集契约（设计 02 §6）——状态推导真值表的输出域，码值稳定。 */
class MigrationStateTest {

    @Test
    void constants_match_design_in_declaration_order() {
        assertThat(EnumSet.allOf(MigrationState.class))
                .containsExactly(
                        MigrationState.PENDING,
                        MigrationState.OUT_OF_ORDER,
                        MigrationState.SUCCESS,
                        MigrationState.FAILED,
                        MigrationState.MISSING,
                        MigrationState.OUTDATED,
                        MigrationState.FUTURE,
                        MigrationState.BASELINE,
                        MigrationState.UNDONE);
    }
}
