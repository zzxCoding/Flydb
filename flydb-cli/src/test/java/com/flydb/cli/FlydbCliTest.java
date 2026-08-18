package com.flydb.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.api.DryRunMigration;
import com.flydb.core.api.DryRunResult;
import com.flydb.core.api.DryRunStatement;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.exception.FlydbValidationException;
import com.flydb.core.exception.ValidationProblem;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlydbCli")
class FlydbCliTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("version 命令输出自身版本并返回成功退出码")
    void versionCommandReturnsSuccess() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                workingDirectory, workingDirectory);

        int exitCode = cli.execute("version");

        assertThat(exitCode).isZero();
        assertThat(standardOutput.toString()).contains("flydb 0.3.0");
        assertThat(errorOutput.toString()).isEmpty();
    }

    @Test
    @DisplayName("--json version 在 stdout 输出单行契约信封，stderr 保持为空")
    void jsonVersionEmitsSingleLineEnvelope() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                workingDirectory, workingDirectory);

        int exitCode = cli.execute("--json", "version");

        assertThat(exitCode).isZero();
        assertThat(standardOutput.toString())
                .isEqualTo("{\"protocolVersion\":1,\"command\":\"version\","
                        + "\"status\":\"success\",\"exitCode\":0,\"version\":\"0.3.0\"}\n");
        assertThat(errorOutput.toString()).isEmpty();
    }

    @Test
    @DisplayName("--json init --yes 输出创建文件清单信封")
    void jsonInitEmitsCreatedFiles() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                temporaryDirectory, temporaryDirectory);

        int exitCode = cli.execute("--json", "init", "--yes",
                "--url", "jdbc:mysql://localhost:3306/app");

        assertThat(exitCode).isZero();
        assertThat(standardOutput.toString())
                .contains("\"command\":\"init\"", "\"createdFiles\":[\"flydb.conf\","
                        + "\"db/migration/V1__init.sql\",\"drivers/README.md\"]");
        assertThat(standardOutput.toString().trim()).doesNotContain("\n");
        assertThat(errorOutput.toString()).isEmpty();
    }

    @Test
    @DisplayName("--json 下配置失败在 stdout 输出错误信封，stderr 保留人类可读消息")
    void jsonFailureEmitsErrorEnvelopeOnStdout() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        Path emptyDirectory = Paths.get(System.getProperty("java.io.tmpdir"),
                "flydb-cli-json-error-" + System.nanoTime());
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                emptyDirectory, emptyDirectory);

        int exitCode = cli.execute("--json", "migrate");

        assertThat(exitCode).isEqualTo(4);
        assertThat(standardOutput.toString())
                .contains("\"status\":\"error\"", "\"exitCode\":4",
                        "\"code\":\"FLYDB-4002\"");
        assertThat(standardOutput.toString().trim()).doesNotContain("\n");
        assertThat(errorOutput.toString()).contains("[FLYDB-4002]");
    }

    @Test
    @DisplayName("--json 下参数用法错误同样输出错误信封（error.code 为 null）")
    void jsonParameterErrorEmitsEnvelope() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                workingDirectory, workingDirectory);

        int exitCode = cli.execute("--json", "migrate", "--no-such-option");

        assertThat(exitCode).isEqualTo(4);
        assertThat(standardOutput.toString())
                .contains("\"command\":\"migrate\"", "\"status\":\"error\"",
                        "\"exitCode\":4", "\"code\":null");
        assertThat(errorOutput.toString()).isNotEmpty();
    }

    @Test
    @DisplayName("缺少 URL 的 migrate 映射为配置退出码 4")
    void missingConfigurationReturnsExitCodeFour() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        Path emptyDirectory = Paths.get(System.getProperty("java.io.tmpdir"),
                "flydb-cli-no-config-" + System.nanoTime());
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                emptyDirectory, emptyDirectory);

        int exitCode = cli.execute("migrate");

        assertThat(exitCode).isEqualTo(4);
        assertThat(errorOutput.toString()).contains("[FLYDB-4002]", "必须提供 flydb.url");
        assertThat(errorOutput.toString()).doesNotContain("at com.flydb");
    }

    @Test
    @DisplayName("init --yes 从 CLI 参数生成可直接编辑的工程骨架")
    void initCreatesScaffoldFromCommandLineOptions() throws Exception {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                temporaryDirectory, temporaryDirectory);

        int exitCode = cli.execute("init", "--yes",
                "--url", "jdbc:mysql://localhost:3306/app",
                "--user", "app_user", "--driver", "com.mysql.cj.jdbc.Driver",
                "--database-type", "mysql");

        assertThat(exitCode).isZero();
        assertThat(errorOutput.toString()).isEmpty();
        assertThat(standardOutput.toString()).contains("flydb.conf", "V1__init.sql");
        assertThat(new String(Files.readAllBytes(temporaryDirectory.resolve("flydb.conf")),
                StandardCharsets.UTF_8)).contains(
                "flydb.url=jdbc:mysql://localhost:3306/app", "flydb.user=app_user",
                "flydb.driver=com.mysql.cj.jdbc.Driver");
    }

    @Test
    @DisplayName("所有子命令都支持 --help 且不触发数据库连接")
    void subcommandsExposeStandardHelp() {
        for (String command : Arrays.asList("migrate", "info", "validate", "baseline",
                "repair", "clean", "undo", "init", "version")) {
            StringWriter standardOutput = new StringWriter();
            StringWriter errorOutput = new StringWriter();
            Path workingDirectory = Paths.get(".").toAbsolutePath().normalize();
            FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                    new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                    workingDirectory, workingDirectory);

            int exitCode = cli.execute(command, "--help");

            assertThat(exitCode).as(command).isZero();
            assertThat(standardOutput.toString()).as(command).contains("Usage: flydb " + command);
            assertThat(errorOutput.toString()).as(command).isEmpty();
        }
    }

    @Test
    @DisplayName("migrate 帮助公开精确版本和版本范围选项")
    void migrateHelpDocumentsVersionSelectionOptions() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                temporaryDirectory, temporaryDirectory);

        int exitCode = cli.execute("migrate", "--help");

        assertThat(exitCode).isZero();
        assertThat(standardOutput.toString()).contains(
                "--target-version", "--start-version", "--end-version",
                "--version-selection", "--version-source", "--version-regex",
                "--directory-glob", "--file-glob", "--path-glob",
                "--directory-regex", "--file-regex", "--path-regex",
                "--migration-order", "--directory-version-regex",
                "--placeholder-replacement");
        assertThat(errorOutput.toString()).isEmpty();
    }

    @Test
    @DisplayName("quiet dry-run 仍输出可审计的计划 ID 和 SQL")
    void quietDryRunStillEmitsPlanIdentity() {
        StringWriter standardOutput = new StringWriter();
        StringWriter errorOutput = new StringWriter();
        FlydbCli cli = new FlydbCli(new PrintWriter(standardOutput, true),
                new PrintWriter(errorOutput, true), Collections.<String, String>emptyMap(),
                temporaryDirectory, temporaryDirectory);
        FlydbCli.RootCommand root = cli.new RootCommand();
        root.quiet = true;
        DryRunResult result = new DryRunResult("migrate", Collections.singletonList(
                new DryRunMigration("V1__init.sql", MigrationType.SQL,
                        MigrationVersion.parse("1"), "init", 10,
                        Collections.singletonList(new DryRunStatement(1, "SELECT 1")))));

        FlydbCli.printDryRun(root, result, null);

        assertThat(standardOutput.toString())
                .contains("计划 flydb-plan-v1/", "-- V1__init.sql [SQL]", "SELECT 1;")
                .doesNotContain("预演完成");
        assertThat(errorOutput.toString()).isEmpty();
    }

    @Test
    @DisplayName("稳定异常类别映射为约定的 CI 退出码")
    void mapsStableFailureCategoriesToExitCodes() {
        FlydbValidationException validation = new FlydbValidationException(
                Collections.singletonList(new ValidationProblem(
                        ErrorCode.CHECKSUM_MISMATCH, "checksum")));

        assertThat(FlydbCli.exitCode(validation)).isEqualTo(2);
        assertThat(FlydbCli.exitCode(new FlydbException(
                ErrorCode.LOCK_ACQUISITION_TIMEOUT, "locked"))).isEqualTo(3);
        assertThat(FlydbCli.exitCode(new FlydbException(
                ErrorCode.MISSING_REQUIRED_CONFIG, "url"))).isEqualTo(4);
        assertThat(FlydbCli.exitCode(new FlydbException(
                ErrorCode.MIGRATION_EXECUTION_FAILED, "sql"))).isEqualTo(1);
    }
}
