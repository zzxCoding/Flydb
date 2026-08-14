package com.flydb.cli.output;

import java.sql.Timestamp;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.MigrationInfoService;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InfoTableRenderer")
class InfoTableRendererTest {

    @Test
    @DisplayName("中文按显示宽度对齐并将状态翻译为稳定中文")
    void alignsChineseTextByDisplayWidth() {
        ResolvedMigration first = ResolvedMigration.of(MigrationVersion.parse("1"),
                "初始化用户", "V1__init.sql", 10, MigrationType.SQL);
        AppliedMigration applied = AppliedMigration.of(1, MigrationVersion.parse("1"),
                "初始化用户", MigrationType.SQL, "V1__init.sql", 10, "flydb",
                Timestamp.valueOf("2026-08-12 09:12:03"), 128, true);
        ResolvedMigration second = ResolvedMigration.of(MigrationVersion.parse("2.1"),
                "add_status", "V2.1__add_status.sql", 20, MigrationType.SQL);
        MigrationInfoService information = new MigrationInfoService(Arrays.asList(
                MigrationInfo.derive(first, applied, MigrationVersion.parse("1"),
                        MigrationVersion.parse("2.1")),
                MigrationInfo.derive(second, null, MigrationVersion.parse("1"),
                        MigrationVersion.parse("2.1"))));

        String table = new InfoTableRenderer().render("0.2.0", "达梦 DM8",
                "jdbc:dm://localhost:5236", "flydb_schema_history", information, false);

        assertThat(table).contains(
                "flydb 0.2.0 · 达梦 DM8 · jdbc:dm://localhost:5236 · 历史表: flydb_schema_history",
                "1           初始化用户              SQL     2026-08-12 09:12:03  128       成功",
                "2.1         add_status              SQL     -                    -         待执行");
        assertThat(table).doesNotContain("\u001B[");
    }

    @Test
    @DisplayName("宽版本号按内容自适应列宽，不挤歪后续列")
    void wideVersionsExpandColumnWidth() {
        ResolvedMigration resolved = ResolvedMigration.of(
                MigrationVersion.parse("20260327-b06.4"), "data",
                "V20260327-b06.4__data.sql", 30, MigrationType.SQL);
        MigrationInfoService information = new MigrationInfoService(Arrays.asList(
                MigrationInfo.derive(resolved, null, null,
                        MigrationVersion.parse("20260327-b06.4"))));

        String table = new InfoTableRenderer().render("0.2.0", "MySQL",
                "jdbc:mysql://localhost/test", "flydb_schema_history", information, false);

        // 版本列宽 = max(保底 10, 表头 4, 内容 14) = 14：表头补到 14 后接 2 空格分隔
        assertThat(table).contains("版本" + "            " + "描述");
        assertThat(table).contains("20260327-b06.4  data");
        assertThat(table).contains("--------------  ----------------------");
    }
}
