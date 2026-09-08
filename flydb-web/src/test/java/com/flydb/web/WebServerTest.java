package com.flydb.web;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flydb.runtime.state.StateJson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WebServerTest {
    @TempDir Path temporary;
    WebServer server;
    String base, cookie;
    @BeforeEach void start() throws Exception {
        server = new WebServer(temporary.resolve("state"), temporary, temporary,
                Collections.<String, String>emptyMap(), 0, "test");
        server.start(); base = server.uri().toString().split("/#")[0];
    }
    @AfterEach void stop() { server.close(); }

    @Test void cleanConfirmationIsSingleUseAndRequestRetriesDoNotReplay() throws Exception {
        // Deliberately unavailable driver: admission is exercised without ever connecting to a database.
        bootstrap(); String profileId = importConfig().path("id").asText();
        String revision = request("GET", "/api/profiles/" + profileId + "/config", null, null).body.path("revision").asText();
        ObjectNode prepare = StateJson.object().put("revision", revision);
        JsonNode confirmation = request("POST", "/api/profiles/" + profileId + "/clean-confirmation", prepare.toString(), null).body;
        ObjectNode action = prepare.deepCopy().put("command", "clean").put("requestId", UUID.randomUUID().toString())
                .put("cleanToken", confirmation.path("token").asText()).put("confirmed", true).put("confirmationText", "clean");
        assertThat(request("POST", "/api/profiles/" + profileId + "/actions", action.toString(), null).status).isEqualTo(400);
        action.put("confirmationText", "CLEAN");
        Reply accepted = request("POST", "/api/profiles/" + profileId + "/actions", action.toString(), null);
        assertThat(accepted.status).isEqualTo(200);
        String id = accepted.body.path("id").asText();
        JsonNode result = accepted.body;
        for (int attempt = 0; attempt < 100 && "RUNNING".equals(result.path("status").asText()); attempt++) {
            Thread.sleep(20); result = request("GET", "/api/runs/" + id, null, null).body;
        }
        assertThat(result.path("result").path("error").path("code").asText()).isEqualTo("FLYDB-1003");
        assertThat(request("POST", "/api/profiles/" + profileId + "/actions", action.toString(), null).body.path("id").asText()).isEqualTo(id);
        action.put("requestId", UUID.randomUUID().toString());
        assertThat(request("POST", "/api/profiles/" + profileId + "/actions", action.toString(), null).status).isEqualTo(409);
        assertThat(request("GET", "/api/bootstrap", null, null).body.path("runs").size()).isEqualTo(1);
        assertThat(new String(Files.readAllBytes(temporary.resolve("flydb.conf")), StandardCharsets.UTF_8)).doesNotContain("clean-disabled=false");
    }

    @Test void cleanRequiresExplicitConfirmationBoundToTheConfiguration() throws Exception {
        bootstrap(); String profileId = importConfig().path("id").asText();
        String revision = request("GET", "/api/profiles/" + profileId + "/config", null, null).body.path("revision").asText();
        ObjectNode prepare = StateJson.object().put("revision", revision);
        Reply preview = request("POST", "/api/profiles/" + profileId + "/clean-confirmation", prepare.toString(), null);
        assertThat(preview.status).isEqualTo(200);
        assertThat(preview.body.path("target").asText()).contains("jdbc:");
        ObjectNode action = prepare.deepCopy().put("command", "clean").put("requestId", UUID.randomUUID().toString());
        assertThat(request("POST", "/api/profiles/" + profileId + "/actions", action.toString(), null).status).isEqualTo(400);
        action.put("confirmed", true).put("confirmationText", "CLEAN");
        assertThat(request("POST", "/api/profiles/" + profileId + "/actions", action.toString(), null).status).isEqualTo(400);
        action.put("cleanToken", preview.body.path("token").asText());
        Files.write(temporary.resolve("flydb.conf"), "\n# changed after confirmation\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        action.put("revision", request("GET", "/api/profiles/" + profileId + "/config", null, null).body.path("revision").asText());
        assertThat(request("POST", "/api/profiles/" + profileId + "/actions", action.toString(), null).status).isEqualTo(409);
        assertThat(request("GET", "/api/bootstrap", null, null).body.path("runs").size()).isZero();
        assertThat(new String(Files.readAllBytes(temporary.resolve("flydb.conf")), StandardCharsets.UTF_8)).doesNotContain("clean-disabled=false");
    }

    @Test void compactPollingOmitsSqlButFullRunPreservesTheEntirePreview() throws Exception {
        com.flydb.runtime.state.RunStore store = new com.flydb.runtime.state.RunStore(temporary.resolve("state"));
        ObjectNode result = StateJson.object().put("planId", "confirmed-plan");
        ObjectNode migration = result.putArray("migrations").addObject().put("script", "V1__large.sql");
        char[] chars = new char[9 * 1024 * 1024]; Arrays.fill(chars, 'x');
        migration.putArray("statements").addObject().put("lineNumber", 1).put("sql", new String(chars));
        String id;
        try (com.flydb.runtime.state.RunStore.Writer writer = store.start(StateJson.object().put("command", "plan"))) {
            id = writer.id(); writer.finish("SUCCEEDED", result, "NOT_RUN");
        }
        bootstrap();
        JsonNode compact = request("GET", "/api/bootstrap?compact=true", null, null).body.path("runs").get(0);
        assertThat(compact.toString().length()).isLessThan(4096);
        assertThat(compact.path("detailsOmitted").asBoolean()).isTrue();
        assertThat(compact.path("result").path("planId").asText()).isEqualTo("confirmed-plan");
        JsonNode full = request("GET", "/api/runs/" + id, null, null).body;
        assertThat(full.path("result").path("migrations").get(0).path("statements").get(0).path("sql").asText().length()).isEqualTo(chars.length);
        assertThat(request("GET", "/api/bootstrap?compact=true", null, null).body.path("runs").get(0)).isEqualTo(compact);
    }

    @Test void automaticBrowserBootstrapNeedsNoAccountAndBlocksCrossOriginWrites() throws Exception {
        assertThat(request("GET", "/api/bootstrap", null, null).status).isEqualTo(401);
        bootstrap();
        assertThat(request("GET", "/api/bootstrap", null, null).body.path("profiles").size()).isZero();
        assertThat(request("POST", "/api/profiles", "{}", "https://untrusted.example").status).isEqualTo(403);
        assertThat(request("GET", "/api/session", null, null).status).isEqualTo(405);
    }
    @Test void freshBrowserCanConnectFromThePlainLocalAddress() throws Exception {
        assertThat(request("POST", "/api/session", "{}", base).status).isEqualTo(200);
        assertThat(request("GET", "/api/bootstrap", null, null).status).isEqualTo(200);
        cookie = null;
        assertThat(request("POST", "/api/session", "{}", "https://untrusted.example").status).isEqualTo(403);
        assertThat(request("POST", "/api/session", "{}", null).status).isEqualTo(403);
        assertThat(request("POST", "/api/session", "{}", "null").status).isEqualTo(403);
    }
    @Test void explicitFileEditorRoundTripsCommentsAndSecretsButOrdinarySnapshotsRemainMasked() throws Exception {
        bootstrap(); String id = importConfig().path("id").asText();
        Path file = temporary.resolve("flydb.conf");
        String original = "# preserved\r\nflydb.url=jdbc:oracle:thin:@//localhost:1521/XEPDB1\r\nflydb.password=synthetic-editor-password\r\ncustom=one\\\r\n  two\r\n";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));
        JsonNode doc = request("GET", "/api/profiles/" + id + "/document", null, null).body;
        assertThat(doc.path("content").asText()).isEqualTo(original);
        assertThat(request("GET", "/api/profiles/" + id + "/config", null, null).body.toString()).doesNotContain("synthetic-editor-password");
        ObjectNode edit = StateJson.object().put("content", original).put("validate", true);
        edit.set("values", StateJson.object().put("flydb.user", "operator"));
        Reply preview = request("POST", "/api/config/document", edit.toString(), null);
        assertThat(preview.status).isEqualTo(200);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).isEqualTo(original);
        String updated = preview.body.path("content").asText();
        assertThat(updated).startsWith(original).endsWith("flydb.user=operator\r\n");
        ObjectNode save = StateJson.object().put("revision", doc.path("revision").asText()).put("content", updated);
        assertThat(request("PUT", "/api/profiles/" + id + "/config", save.toString(), null).status).isEqualTo(200);
        assertThat(request("PUT", "/api/profiles/" + id + "/config", save.toString(), null).status).isEqualTo(409);
    }
    @Test void malformedOrInvalidSourceCannotOverwriteAConfiguration() throws Exception {
        bootstrap(); String id = importConfig().path("id").asText();
        Path file = temporary.resolve("flydb.conf"); byte[] original = Files.readAllBytes(file);
        String revision = request("GET", "/api/profiles/" + id + "/document", null, null).body.path("revision").asText();
        for (String content : Arrays.asList("flydb.user=\\uINVALID\n", "flydb.batch-size=0\n", "flydb.unknown-option=yes\n")) {
            ObjectNode input = StateJson.object().put("content", content).put("revision", revision).put("validate", false);
            assertThat(request("PUT", "/api/profiles/" + id + "/config", input.toString(), null).status).isEqualTo(400);
            assertThat(Files.readAllBytes(file)).isEqualTo(original);
        }
    }
    @Test void previewsInitWithoutWritingAndCreatesTheSameScaffold() throws Exception {
        bootstrap(); Path work = temporary.resolve("init-preview");
        ObjectNode input = StateJson.object().put("workingDirectory", work.toString())
                .put("url", "jdbc:oracle:thin:@//localhost:1521/XEPDB1").put("user", "operator");
        Reply draft = request("POST", "/api/config/init", input.toString(), null);
        assertThat(draft.status).isEqualTo(200);
        assertThat(Files.exists(work)).isFalse();
        String content = draft.body.path("content").asText();
        assertThat(content).contains("# Flydb", "flydb.clean-disabled=true");
        assertThat(draft.body.path("values").path("flydb.locations").asText())
                .isEqualTo("filesystem:" + work.resolve("db/migration"));
        Path reference = temporary.resolve("cli-reference");
        new com.flydb.runtime.init.InitScaffolder().create(reference, input.path("url").asText(), "operator", null);
        assertThat(content).isEqualTo(new String(Files.readAllBytes(reference.resolve("flydb.conf")), StandardCharsets.UTF_8)
                .replace(reference.toString(), work.toString()));
        input.put("mode", "create").put("content", content + "# my note\ncustom.note=preserved\n");
        assertThat(request("POST", "/api/profiles", input.toString(), null).status).isEqualTo(200);
        assertThat(new String(Files.readAllBytes(work.resolve("flydb.conf")), StandardCharsets.UTF_8))
                .isEqualTo(input.path("content").asText());
        for (String name : Arrays.asList("db/migration/V1__init.sql", "drivers/README.md"))
            assertThat(Files.readAllBytes(work.resolve(name))).isEqualTo(Files.readAllBytes(reference.resolve(name)));
    }
    @Test void initConflictPreservesExistingMigrationAndDoesNotCreateConfigOrRegister() throws Exception {
        bootstrap(); Path work = temporary.resolve("existing-project");
        Path migration = work.resolve("db/migration/V1__init.sql");
        Files.createDirectories(migration.getParent()); Files.write(migration, "business SQL".getBytes(StandardCharsets.UTF_8));
        ObjectNode input = StateJson.object().put("mode", "create").put("workingDirectory", work.toString())
                .put("content", "flydb.url=jdbc:mysql://localhost/app\n");
        assertThat(request("POST", "/api/profiles", input.toString(), null).status).isEqualTo(409);
        assertThat(Files.exists(work.resolve("flydb.conf"))).isFalse();
        assertThat(new String(Files.readAllBytes(migration), StandardCharsets.UTF_8)).isEqualTo("business SQL");
        assertThat(request("GET", "/api/bootstrap", null, null).body.path("profiles").size()).isZero();
    }
    @Test void createsACompleteDocumentWithoutConnectingOrOverwritingFiles() throws Exception {
        bootstrap(); Path work = temporary.resolve("created");
        String content = "# authored config\nflydb.url=jdbc:oracle:thin:@//localhost:1521/XEPDB1\nflydb.password=${env:UNSET_EDITOR_TEST}\nflydb.locations=filesystem:db/migration\n";
        ObjectNode input = StateJson.object().put("mode", "create").put("workingDirectory", work.toString()).put("content", content);
        assertThat(request("POST", "/api/profiles", input.toString(), null).status).isEqualTo(200);
        assertThat(new String(Files.readAllBytes(work.resolve("flydb.conf")), StandardCharsets.UTF_8)).isEqualTo(content);
        assertThat(Files.isDirectory(work.resolve("db/migration"))).isTrue();
        assertThat(request("POST", "/api/profiles", input.toString(), null).status).isEqualTo(409);
        Path invalid = temporary.resolve("invalid");
        input.put("workingDirectory", invalid.toString()).put("content", "flydb.batch-size=0");
        assertThat(request("POST", "/api/profiles", input.toString(), null).status).isEqualTo(400);
        assertThat(Files.exists(invalid)).isFalse();
    }
    @Test void savesOriginalFileAndRefusesAnExternalOverwrite() throws Exception {
        bootstrap(); ObjectNode profile = importConfig(); String id = profile.path("id").asText();
        Reply loaded = request("GET", "/api/profiles/" + id + "/config", null, null);
        String revision = loaded.body.path("revision").asText();
        ObjectNode update = StateJson.object().put("revision", revision);
        update.set("values", StateJson.object().put("flydb.user", "new-user"));
        assertThat(request("PUT", "/api/profiles/" + id + "/config", update.toString(), null).status).isEqualTo(200);
        String saved = new String(Files.readAllBytes(temporary.resolve("flydb.conf")), StandardCharsets.UTF_8);
        assertThat(saved).contains("# keep this comment\r\n", "flydb.user=new-user\r\n");
        assertThat(request("PUT", "/api/profiles/" + id + "/config", update.toString(), null).status).isEqualTo(409);
        assertThat(request("DELETE", "/api/profiles/" + id, "{}", null).status).isEqualTo(200);
        assertThat(temporary.resolve("flydb.conf")).exists();
    }
    @Test void driverFailureHasARecordedTerminalResultAndRetryDoesNotCreateAnotherRun() throws Exception {
        bootstrap(); String id = importConfig().path("id").asText();
        String revision = request("GET", "/api/profiles/" + id + "/config", null, null).body.path("revision").asText();
        ObjectNode action = StateJson.object().put("command", "inspect").put("revision", revision)
                .put("requestId", UUID.randomUUID().toString());
        Reply response = request("POST", "/api/profiles/" + id + "/actions", action.toString(), null);
        assertThat(response.status).isEqualTo(200); String run = response.body.path("id").asText();
        JsonNode result = response.body;
        for (int i = 0; i < 100 && "RUNNING".equals(result.path("status").asText()); i++) {
            Thread.sleep(20); result = request("GET", "/api/runs/" + run, null, null).body;
        }
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.path("result").path("error").path("code").asText()).isEqualTo("FLYDB-1003");
        assertThat(request("POST", "/api/profiles/" + id + "/actions", action.toString(), null).body.path("id").asText()).isEqualTo(run);
    }
    @Test void invalidTypedValuesAreRejectedBeforeTheFileIsChanged() throws Exception {
        bootstrap(); String id = importConfig().path("id").asText();
        String revision = request("GET", "/api/profiles/" + id + "/config", null, null).body.path("revision").asText();
        byte[] original = Files.readAllBytes(temporary.resolve("flydb.conf"));
        ObjectNode update = StateJson.object().put("revision", revision);
        update.set("values", StateJson.object().put("flydb.batch-size", "0"));
        Reply reply = request("PUT", "/api/profiles/" + id + "/config", update.toString(), null);
        assertThat(reply.status).isEqualTo(400);
        assertThat(reply.body.path("code").asText()).isEqualTo("FLYDB-4002");
        assertThat(Files.readAllBytes(temporary.resolve("flydb.conf"))).isEqualTo(original);
        update.set("values", StateJson.object().put("flydb.password", "${env:NOT_SET_DURING_EDIT}"));
        assertThat(request("PUT", "/api/profiles/" + id + "/config", update.toString(), null).status).isEqualTo(200);
    }
    @Test void credentialUrlsAreNeverSentAsEditableMasksOrOverwrittenOnUnrelatedSave() throws Exception {
        bootstrap(); String id = importConfig().path("id").asText();
        Path file = temporary.resolve("flydb.conf");
        Files.write(file, "flydb.url=jdbc:oracle:thin:tester/synthetic-secret@//localhost/service\nflydb.user=tester\n".getBytes(StandardCharsets.UTF_8));
        JsonNode config = request("GET", "/api/profiles/" + id + "/config", null, null).body;
        assertThat(config.toString()).doesNotContain("synthetic-secret");
        assertThat(config.path("values").has("flydb.url")).isFalse();
        assertThat(config.path("effective").path("flydb.url").asText()).contains("****");
        ObjectNode update = StateJson.object().put("revision", config.path("revision").asText());
        update.set("values", StateJson.object().put("flydb.user", "new-user"));
        assertThat(request("PUT", "/api/profiles/" + id + "/config", update.toString(), null).status).isEqualTo(200);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).contains("tester/synthetic-secret@");
    }
    private void bootstrap() throws Exception {
        String key = server.uri().getFragment().substring(4);
        assertThat(request("POST", "/api/session", StateJson.object().put("key", key).toString(), null).status).isEqualTo(200);
    }
    private ObjectNode importConfig() throws Exception {
        Files.write(temporary.resolve("flydb.conf"), ("# keep this comment\r\nflydb.url=jdbc:missing:test\r\nflydb.user=tester\r\nflydb.driver=missing.TestDriver\r\nflydb.offline=true\r\n").getBytes(StandardCharsets.UTF_8));
        ObjectNode input = StateJson.object().put("mode", "import").put("workingDirectory", temporary.toString())
                .put("configPath", temporary.resolve("flydb.conf").toString());
        Reply response = request("POST", "/api/profiles", input.toString(), null);
        assertThat(response.status).isEqualTo(200); return (ObjectNode) response.body.get(0);
    }
    private Reply request(String method, String path, String body, String origin) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000); connection.setRequestMethod(method);
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        if (origin != null) connection.setRequestProperty("Origin", origin);
        if (body != null) {
            connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = connection.getResponseCode();
        if (connection.getHeaderField("Set-Cookie") != null) cookie = connection.getHeaderField("Set-Cookie").split(";", 2)[0];
        try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            return new Reply(status, StateJson.MAPPER.readTree(in));
        } finally { connection.disconnect(); }
    }
    private static final class Reply {
        final int status; final JsonNode body;
        Reply(int status, JsonNode body) { this.status = status; this.body = body; }
    }
}
