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

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.exception.FlydbValidationException;
import com.flydb.core.exception.ValidationProblem;

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
        assertThat(standardOutput.toString()).contains("flydb 2.0.0-SNAPSHOT");
        assertThat(errorOutput.toString()).isEmpty();
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
