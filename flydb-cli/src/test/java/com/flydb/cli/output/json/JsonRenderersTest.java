package com.flydb.cli.output.json;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.DryRunMigration;
import com.flydb.core.api.DryRunResult;
import com.flydb.core.api.DryRunStatement;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.MigrationInfoService;
import com.flydb.core.api.RepairResult;
import com.flydb.core.api.UndoResult;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.ValidationProblem;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机器契约信封的精确字符串回归测试（设计 10）。
 *
 * <p>断言完整 JSON 而非子串：任何字段改名、删除、顺序调整都视为破坏契约，
 * 必须递增 protocolVersion 并同步设计文档。
 */
@DisplayName("JsonRenderers 机器契约信封")
class JsonRenderersTest {

    @Test
    @DisplayName("version 信封携带产品版本")
    void versionEnvelope() {
        assertThat(JsonRenderers.version("0.3.0"))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"version\","
                        + "\"status\":\"success\",\"exitCode\":0,\"version\":\"0.3.0\"}");
    }

    @Test
    @DisplayName("migrate 信封包含执行清单、目标版本、耗时与警告")
    void migrateEnvelope() {
        MigrateResult result = new MigrateResult(
                Arrays.asList("V1__init.sql", "V2__add_order.sql"),
                MigrationVersion.parse("2"), 842L,
                Collections.singletonList("range 不含结束版本族子版本"));

        assertThat(JsonRenderers.migrate(result))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"migrate\",\"status\":\"success\","
                        + "\"exitCode\":0,\"executed\":[\"V1__init.sql\",\"V2__add_order.sql\"],"
                        + "\"targetVersionReached\":\"2\",\"totalExecutionTimeMillis\":842,"
                        + "\"warnings\":[\"range 不含结束版本族子版本\"]}");
    }

    @Test
    @DisplayName("dry-run 信封保留语句行号并对 SQL 脱敏")
    void dryRunEnvelopeRedactsSql() {
        DryRunResult result = new DryRunResult(Collections.singletonList(new DryRunMigration(
                "V2__add_order.sql", MigrationType.SQL, Arrays.asList(
                new DryRunStatement(1, "CREATE TABLE orders (id INT)"),
                new DryRunStatement(3, "CREATE USER app IDENTIFIED BY 's3cret'")))));

        assertThat(JsonRenderers.dryRun("migrate", result, "s3cret"))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"migrate\",\"status\":\"success\","
                        + "\"exitCode\":0,\"dryRun\":true,\"migrations\":[{\"script\":"
                        + "\"V2__add_order.sql\",\"type\":\"SQL\",\"statements\":["
                        + "{\"lineNumber\":1,\"sql\":\"CREATE TABLE orders (id INT)\"},"
                        + "{\"lineNumber\":3,\"sql\":\"CREATE USER app IDENTIFIED BY '****'\"}]}]}");
    }

    @Test
    @DisplayName("info 信封携带数据库、脱敏 URL、当前版本与逐迁移状态")
    void infoEnvelope() {
        MigrationVersion v2 = MigrationVersion.parse("2.1");
        ResolvedMigration resolvedApplied = ResolvedMigration.of(v2, "fix_status_default",
                "V2.1__fix_status_default.sql", Integer.valueOf(12345), MigrationType.SQL);
        AppliedMigration applied = AppliedMigration.of(1, v2, "fix_status_default",
                MigrationType.SQL, "V2.1__fix_status_default.sql", Integer.valueOf(12345), "dba",
                Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 9, 12, 3)), 45, true);
        MigrationInfo appliedInfo = MigrationInfo.derive(resolvedApplied, applied, v2, v2);

        MigrationVersion v3 = MigrationVersion.parse("3");
        ResolvedMigration resolvedPending = ResolvedMigration.of(v3, "add_status_column",
                "V3__add_status_column.sql", Integer.valueOf(999), MigrationType.SQL);
        MigrationInfo pendingInfo = MigrationInfo.derive(resolvedPending, null, v2, v3);

        MigrationInfoService information = new MigrationInfoService(
                Arrays.asList(appliedInfo, pendingInfo), "达梦 DM8");

        assertThat(JsonRenderers.info("达梦 DM8", "jdbc:dm://10.0.0.1:5236",
                "flydb_schema_history", information))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"info\",\"status\":\"success\","
                        + "\"exitCode\":0,\"databaseName\":\"达梦 DM8\","
                        + "\"url\":\"jdbc:dm://10.0.0.1:5236\",\"historyTable\":"
                        + "\"flydb_schema_history\",\"current\":\"2.1\",\"migrations\":["
                        + "{\"version\":\"2.1\",\"description\":\"fix_status_default\","
                        + "\"type\":\"SQL\",\"script\":\"V2.1__fix_status_default.sql\","
                        + "\"checksum\":12345,\"installedOn\":\"2026-08-10T09:12:03\","
                        + "\"executionTimeMillis\":45,\"state\":\"SUCCESS\"},"
                        + "{\"version\":\"3\",\"description\":\"add_status_column\","
                        + "\"type\":\"SQL\",\"script\":\"V3__add_status_column.sql\","
                        + "\"checksum\":999,\"installedOn\":null,\"executionTimeMillis\":null,"
                        + "\"state\":\"PENDING\"}]}");
    }

    @Test
    @DisplayName("info 信封对 URL 内嵌凭据脱敏")
    void infoEnvelopeRedactsUrlCredentials() {
        MigrationInfoService empty = new MigrationInfoService(
                Collections.<MigrationInfo>emptyList(), "MySQL");

        assertThat(JsonRenderers.info("MySQL", "jdbc:mysql://flydb:secret@127.0.0.1:3306/app",
                "flydb_schema_history", empty))
                .contains("\"url\":\"jdbc:mysql://flydb:****@127.0.0.1:3306/app\"")
                .contains("\"current\":null,\"migrations\":[]");
    }

    @Test
    @DisplayName("repair 与 undo 信封")
    void repairAndUndoEnvelopes() {
        assertThat(JsonRenderers.repair(new RepairResult(
                Collections.singletonList("3"), Collections.<String>emptyList())))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"repair\",\"status\":\"success\","
                        + "\"exitCode\":0,\"removedFailedRecords\":[\"3\"],"
                        + "\"alignedChecksums\":[]}");
        assertThat(JsonRenderers.undo(new UndoResult(MigrationVersion.parse("2"), 120L)))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"undo\",\"status\":\"success\","
                        + "\"exitCode\":0,\"undoneVersion\":\"2\",\"executionTimeMillis\":120}");
    }

    @Test
    @DisplayName("init、baseline、validate、clean 信封")
    void simpleEnvelopes() {
        assertThat(JsonRenderers.init(Arrays.asList("flydb.conf", "db/migration/V1__init.sql",
                "drivers/README.md")))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"init\",\"status\":\"success\","
                        + "\"exitCode\":0,\"createdFiles\":[\"flydb.conf\","
                        + "\"db/migration/V1__init.sql\",\"drivers/README.md\"]}");
        assertThat(JsonRenderers.baseline("20260801"))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"baseline\",\"status\":\"success\","
                        + "\"exitCode\":0,\"baselineVersion\":\"20260801\"}");
        assertThat(JsonRenderers.validate())
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"validate\","
                        + "\"status\":\"success\",\"exitCode\":0}");
        assertThat(JsonRenderers.clean())
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"clean\","
                        + "\"status\":\"success\",\"exitCode\":0}");
    }

    @Test
    @DisplayName("错误信封携带错误码、脱敏详情与校验问题清单")
    void errorEnvelope() {
        assertThat(JsonRenderers.error("migrate", 4, "FLYDB-4002",
                "必须提供 flydb.url", Collections.<ValidationProblem>emptyList()))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"migrate\",\"status\":\"error\","
                        + "\"exitCode\":4,\"error\":{\"code\":\"FLYDB-4002\",\"detail\":"
                        + "\"必须提供 flydb.url\",\"problems\":[]}}");

        assertThat(JsonRenderers.error("validate", 2, "FLYDB-2003", null,
                Arrays.asList(
                        new ValidationProblem(ErrorCode.CHECKSUM_MISMATCH, "V2 与历史不一致"),
                        new ValidationProblem(ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR, "V3 留有失败记录"))))
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"validate\",\"status\":\"error\","
                        + "\"exitCode\":2,\"error\":{\"code\":\"FLYDB-2003\",\"detail\":null,"
                        + "\"problems\":[{\"code\":\"FLYDB-2003\",\"detail\":\"V2 与历史不一致\"},"
                        + "{\"code\":\"FLYDB-2004\",\"detail\":\"V3 留有失败记录\"}]}}");

        assertThat(JsonRenderers.error(null, 1, null, "boom", null))
                .isEqualTo("{\"protocolVersion\":1,\"command\":null,\"status\":\"error\","
                        + "\"exitCode\":1,\"error\":{\"code\":null,\"detail\":\"boom\","
                        + "\"problems\":[]}}");
    }
}
