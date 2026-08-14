package com.flydb.cli.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConfigLoader")
class ConfigLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("CLI 参数覆盖环境变量，环境变量覆盖 UTF-8 配置文件，未配置项使用 CLI 默认值")
    void mergesFourLayersInDocumentedOrder() throws Exception {
        Files.write(temporaryDirectory.resolve("flydb.conf"), (
                "flydb.url=jdbc:mysql://file/db\n"
                        + "flydb.table=文件历史表\n"
                        + "flydb.placeholders.tenant=file-tenant\n")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("FLYDB_URL", "jdbc:mysql://env/db");
        environment.put("FLYDB_TABLE", "env_history");
        environment.put("FLYDB_PLACEHOLDERS_TENANT", "env-tenant");
        Map<String, String> cli = Collections.singletonMap(
                "flydb.url", "jdbc:mysql://cli/db");

        CliConfiguration configuration = new ConfigLoader().load(
                null, temporaryDirectory, temporaryDirectory.resolve("install"), environment, cli);

        assertThat(configuration.url()).isEqualTo("jdbc:mysql://cli/db");
        assertThat(configuration.table()).isEqualTo("env_history");
        assertThat(configuration.locations()).containsExactly("filesystem:db/migration");
        assertThat(configuration.encoding().name()).isEqualTo("UTF-8");
        assertThat(configuration.placeholders()).containsEntry("tenant", "env-tenant");
    }

    @Test
    @DisplayName("版本范围支持配置文件、环境变量和 CLI 覆盖")
    void mergesVersionSelectionAcrossConfigurationLayers() throws Exception {
        Files.write(temporaryDirectory.resolve("flydb.conf"), (
                "flydb.url=jdbc:mysql://file/db\n"
                        + "flydb.start-version=2\n"
                        + "flydb.end-version=8\n")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("FLYDB_END_VERSION", "6");
        Map<String, String> cli = Collections.singletonMap(
                "flydb.start-version", "3");

        CliConfiguration configuration = new ConfigLoader().load(
                null, temporaryDirectory, temporaryDirectory.resolve("install"), environment, cli);

        assertThat(configuration.targetVersion()).isNull();
        assertThat(configuration.startVersion()).isEqualTo("3");
        assertThat(configuration.endVersion()).isEqualTo("6");
    }

    @Test
    @DisplayName("版本规则、路径过滤和排序规则贯通配置文件、环境变量与 CLI")
    void mergesAdvancedMigrationRulesAcrossConfigurationLayers() throws Exception {
        Files.write(temporaryDirectory.resolve("flydb.conf"), (
                "flydb.url=jdbc:mysql://file/db\n"
                        + "flydb.version-selection=family\n"
                        + "flydb.version-source=directory\n"
                        + "flydb.target-version=20230531\n"
                        + "flydb.directory-glob=mysql/param/**\n")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("FLYDB_FILE_GLOB", "V*__*.sql");
        Map<String, String> cli = Collections.singletonMap(
                "flydb.migration-order", "directory-version");

        CliConfiguration configuration = new ConfigLoader().load(
                null, temporaryDirectory, temporaryDirectory.resolve("install"), environment, cli);

        assertThat(configuration.versionSelection()).isEqualTo("family");
        assertThat(configuration.versionSource()).isEqualTo("directory");
        assertThat(configuration.directoryGlob()).isEqualTo("mysql/param/**");
        assertThat(configuration.fileGlob()).isEqualTo("V*__*.sql");
        assertThat(configuration.migrationOrder()).isEqualTo("directory-version");
    }

    @Test
    @DisplayName("占位符替换总开关支持环境变量覆盖且默认开启")
    void mergesPlaceholderReplacementSwitch() {
        Map<String, String> environment = Collections.singletonMap(
                "FLYDB_PLACEHOLDER_REPLACEMENT", "false");

        CliConfiguration configuration = new ConfigLoader().load(
                null, temporaryDirectory, temporaryDirectory.resolve("install"), environment,
                Collections.singletonMap("flydb.url", "jdbc:mysql://localhost/db"));

        assertThat(configuration.placeholderReplacement()).isFalse();
    }

    @Test
    @DisplayName("配置文件包含未知 flydb 键时列出键名和最接近的建议")
    void rejectsUnknownFlydbKeyWithSuggestion() throws Exception {
        Files.write(temporaryDirectory.resolve("flydb.conf"), (
                "flydb.url=jdbc:mysql://localhost/db\n"
                        + "flydb.validate-on-migrat=true\n")
                .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new ConfigLoader().load(null, temporaryDirectory,
                temporaryDirectory.resolve("install"), Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap()))
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode(),
                        error -> ((FlydbException) error).detail())
                .containsExactly(ErrorCode.UNKNOWN_CONFIG_KEY,
                        "未知键 flydb.validate-on-migrat；是否想写 flydb.validate-on-migrate？");
    }

    @Test
    @DisplayName("密码支持环境变量间接引用，且不会保留引用表达式")
    void resolvesPasswordFromIndirectEnvironmentReference() throws Exception {
        Files.write(temporaryDirectory.resolve("flydb.conf"), (
                "flydb.url=jdbc:mysql://localhost/db\n"
                        + "flydb.password=${env:DB_PASSWORD}\n")
                .getBytes(StandardCharsets.UTF_8));
        Map<String, String> environment = Collections.singletonMap(
                "DB_PASSWORD", "s3cret-value");

        CliConfiguration configuration = new ConfigLoader().load(null, temporaryDirectory,
                temporaryDirectory.resolve("install"), environment,
                Collections.<String, String>emptyMap());

        assertThat(configuration.password()).isEqualTo("s3cret-value");
    }

    @Test
    @DisplayName("非法脚本编码统一映射为配置错误")
    void rejectsUnsupportedEncodingAsConfigurationError() {
        Map<String, String> cli = new HashMap<String, String>();
        cli.put("flydb.url", "jdbc:mysql://localhost/db");
        cli.put("flydb.encoding", "NOT-A-REAL-CHARSET");
        CliConfiguration configuration = new ConfigLoader().load(null, temporaryDirectory,
                temporaryDirectory.resolve("install"), Collections.<String, String>emptyMap(), cli);

        assertThatThrownBy(configuration::encoding)
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.MISSING_REQUIRED_CONFIG);
    }
}
