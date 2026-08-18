package com.flydb.cli.output.json;

import java.text.SimpleDateFormat;
import java.util.List;

import com.flydb.cli.output.SecretRedactor;
import com.flydb.core.api.DryRunMigration;
import com.flydb.core.api.DryRunResult;
import com.flydb.core.api.DryRunStatement;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.MigrationInfoService;
import com.flydb.core.api.RepairResult;
import com.flydb.core.api.UndoResult;
import com.flydb.core.exception.ValidationProblem;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

/**
 * 机器契约 JSON 信封渲染（设计 10）。纯函数：入参为命令结果领域对象，
 * 出参为单行紧凑 JSON 字符串，风格对齐 {@code InfoTableRenderer}。
 *
 * <p>契约承诺：字段顺序固定；同一 protocolVersion 内只追加字段，不改名、
 * 不删除、不改类型或语义；状态与类型用枚举名作稳定 token。
 */
public final class JsonRenderers {

    /** 命令、配置、错误码与 JSON schema 的契约版本；破坏性变更时递增。 */
    public static final int PROTOCOL_VERSION = 1;

    private JsonRenderers() {
    }

    public static String version(String flydbVersion) {
        return envelope("version")
                .name("version").value(flydbVersion)
                .endObject().toString();
    }

    public static String validate() {
        return envelope("validate").endObject().toString();
    }

    public static String baseline(String baselineVersion) {
        return envelope("baseline")
                .name("baselineVersion").value(baselineVersion)
                .endObject().toString();
    }

    public static String clean() {
        return envelope("clean").endObject().toString();
    }

    public static String init(List<String> createdFiles) {
        JsonWriter json = envelope("init").name("createdFiles").beginArray();
        for (String file : createdFiles) json.value(file);
        return json.endArray().endObject().toString();
    }

    public static String migrate(MigrateResult result) {
        JsonWriter json = envelope("migrate").name("executed").beginArray();
        for (String script : result.executed()) json.value(script);
        json.endArray()
                .name("targetVersionReached").value(versionText(result.targetVersionReached()))
                .name("totalExecutionTimeMillis").value(result.totalExecutionTimeMillis())
                .name("warnings").beginArray();
        for (String warning : result.warnings()) json.value(warning);
        return json.endArray().endObject().toString();
    }

    /** dry-run 信封；command 为 {@code migrate} 或 {@code undo}，SQL 与文本模式同样脱敏。 */
    public static String dryRun(String command, DryRunResult result, String password) {
        JsonWriter json = envelope(command)
                .name("dryRun").value(true)
                .name("migrations").beginArray();
        for (DryRunMigration migration : result.migrations()) {
            json.beginObject()
                    .name("script").value(migration.script())
                    .name("type").value(migration.type().name())
                    .name("statements").beginArray();
            for (DryRunStatement statement : migration.statements()) {
                json.beginObject()
                        .name("lineNumber").value(statement.lineNumber())
                        .name("sql").value(SecretRedactor.redactSecret(statement.sql(), password))
                        .endObject();
            }
            json.endArray().endObject();
        }
        return json.endArray().endObject().toString();
    }

    public static String info(String databaseName, String url, String historyTable,
                              MigrationInfoService information) {
        JsonWriter json = envelope("info")
                .name("databaseName").value(databaseName)
                .name("url").value(SecretRedactor.redact(url))
                .name("historyTable").value(historyTable)
                .name("current").value(versionText(information.current()))
                .name("migrations").beginArray();
        for (MigrationInfo entry : information.all()) {
            appendMigration(json, entry);
        }
        return json.endArray().endObject().toString();
    }

    public static String repair(RepairResult result) {
        JsonWriter json = envelope("repair").name("removedFailedRecords").beginArray();
        for (String record : result.removedFailedRecords()) json.value(record);
        json.endArray().name("alignedChecksums").beginArray();
        for (String record : result.alignedChecksums()) json.value(record);
        return json.endArray().endObject().toString();
    }

    public static String undo(UndoResult result) {
        return envelope("undo")
                .name("undoneVersion").value(versionText(result.undoneVersion()))
                .name("executionTimeMillis").value(result.executionTimeMillis())
                .endObject().toString();
    }

    /**
     * 失败信封。detail 由调用方完成脱敏；code 为 {@code null} 表示不带
     * Flydb 错误码的失败（如参数用法错误、非预期异常），仅能凭退出码分类。
     */
    public static String error(String command, int exitCode, String code, String detail,
                               List<ValidationProblem> problems) {
        JsonWriter json = new JsonWriter().beginObject()
                .name("protocolVersion").value(PROTOCOL_VERSION)
                .name("command").value(command)
                .name("status").value("error")
                .name("exitCode").value(exitCode)
                .name("error").beginObject()
                .name("code").value(code)
                .name("detail").value(detail)
                .name("problems").beginArray();
        if (problems != null) {
            for (ValidationProblem problem : problems) {
                json.beginObject()
                        .name("code").value(problem.errorCode().code())
                        .name("detail").value(problem.detail())
                        .endObject();
            }
        }
        return json.endArray().endObject().endObject().toString();
    }

    private static void appendMigration(JsonWriter json, MigrationInfo entry) {
        ResolvedMigration resolved = entry.resolved();
        AppliedMigration applied = entry.applied();
        MigrationVersion version = resolved != null ? resolved.version()
                : applied == null ? null : applied.version();
        MigrationType type = resolved != null ? resolved.type()
                : applied == null ? MigrationType.SQL : applied.type();
        json.beginObject()
                .name("version").value(versionText(version))
                .name("description").value(resolved != null ? resolved.description()
                        : applied == null ? null : applied.description())
                .name("type").value(type.name())
                .name("script").value(resolved != null ? resolved.script()
                        : applied == null ? null : applied.script())
                .name("checksum").value(resolved != null ? resolved.checksum()
                        : applied == null ? null : applied.checksum())
                .name("installedOn").value(applied == null || applied.installedOn() == null
                        ? null : formatTimestamp(applied.installedOn()))
                .name("executionTimeMillis").value(applied == null ? null
                        : Integer.valueOf(applied.executionTimeMillis()))
                .name("state").value(entry.state().name())
                .endObject();
    }

    private static JsonWriter envelope(String command) {
        return new JsonWriter().beginObject()
                .name("protocolVersion").value(PROTOCOL_VERSION)
                .name("command").value(command)
                .name("status").value("success")
                .name("exitCode").value(0);
    }

    private static String versionText(MigrationVersion version) {
        return version == null ? null : version.toString();
    }

    private static String formatTimestamp(java.util.Date date) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(date);
    }
}
