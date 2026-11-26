package com.flydb.web;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.flydb.core.exception.FlydbException;
import com.flydb.runtime.config.*;
import com.flydb.runtime.init.MigrationScriptCreator;
import com.flydb.runtime.output.SecretRedactor;
import com.flydb.runtime.state.StateJson;

/** The existing loader is the sole source of effective configuration semantics. */
final class ConfigurationService {
    private final Path installation;
    private final Map<String, String> environment;
    ConfigurationService(Path installation, Map<String, String> environment) {
        this.installation = installation; this.environment = new HashMap<String, String>(environment);
    }
    CliConfiguration load(ObjectNode profile, Map<String, String> overrides) {
        return new ConfigLoader().load(Paths.get(profile.path("configPath").asText()),
                Paths.get(profile.path("workingDirectory").asText()), installation, environment, overrides);
    }
    synchronized ObjectNode createScript(ObjectNode profile, ObjectNode input) throws IOException {
        if (!document(profile).revision().equals(ProfileStore.required(input, "revision")))
            throw new ConfigurationDocument.Conflict();
        Path working = Paths.get(profile.path("workingDirectory").asText());
        Map<String, String> overrides = input.has("releaseDirectory")
                ? Collections.singletonMap("flydb.locations", "filesystem:" + ProfileStore.releaseDirectory(input))
                : Collections.<String, String>emptyMap();
        CliConfiguration config = loadForScripts(profile, working, overrides);
        String version = input.has("scriptVersion") ? ProfileStore.required(input, "scriptVersion") : null;
        Path directory = input.has("directory") ? Paths.get(ProfileStore.required(input, "directory")) : null;
        MigrationScriptCreator.CreatedScript created = MigrationScriptCreator.create(config.discoveryConfiguration(),
                working, ProfileStore.required(input, "description"), version, directory);
        return StateJson.object().put("path", created.path().toString()).put("version", created.version());
    }

    private CliConfiguration loadForScripts(ObjectNode profile, Path working, Map<String, String> overrides) {
        return new ConfigLoader().loadForScripts(Paths.get(profile.path("configPath").asText()),
                working, installation, environment, overrides).inDirectory(working);
    }

    ObjectNode read(ObjectNode profile) throws IOException {
        ConfigurationDocument document = document(profile);
        ObjectNode result = StateJson.object().put("revision", document.revision());
        ArrayNode secrets = StateJson.array();
        result.set("values", visible(document.values(), secrets, true)); result.set("secretKeys", secrets);
        result.set("effective", StateJson.object()); result.set("sources", StateJson.object());
        try {
            CliConfiguration configuration = load(profile, Collections.<String, String>emptyMap())
                    .inDirectory(Paths.get(profile.path("workingDirectory").asText()));
            result.set("effective", visible(configuration.values(), StateJson.array(), false));
            result.set("sources", StateJson.MAPPER.valueToTree(configuration.sources()));
            ObjectNode origins = StateJson.object();
            configuration.declaredAt().forEach((key, origin) -> origins.put(key, SecretRedactor.redact(origin)));
            result.set("declaredAt", origins);
        } catch (FlydbException e) {
            result.set("error", StateJson.object().put("code", e.errorCode().code()).put("detail", SecretRedactor.redact(e.detail())));
            // 连接凭据缺失时，离线建脚本仍可展示继承及环境变量解析后的迁移位置。
            try {
                CliConfiguration scripts = loadForScripts(profile, Paths.get(profile.path("workingDirectory").asText()),
                        Collections.<String, String>emptyMap());
                result.put("scriptLocations", SecretRedactor.redact(scripts.values().get("flydb.locations")));
            } catch (FlydbException invalidDiscoveryConfig) {
                // 目录配置本身也无法解析，保留原诊断。
            }
        }
        return result;
    }
    synchronized ObjectNode save(ObjectNode profile, ObjectNode input) throws IOException {
        if (input.has("content")) {
            // Explicit source editing replaces the document, retaining optimistic concurrency.
            ConfigurationDocument edited = ConfigDocuments.parse(input.deepCopy().put("validate", true));
            ConfigLoader.validateDocumentValues(edited.values(), Paths.get(profile.path("configPath").asText()));
            document(profile).saveContent(edited.content(), ProfileStore.required(input, "revision"));
            return read(profile);
        }
        JsonNode fields = input.path("values");
        if (!fields.isObject() || fields.size() > 200) throw new WebException(400, "INVALID_REQUEST", "Expected at most 200 configuration fields");
        Map<String, String> edits = new LinkedHashMap<String, String>();
        fields.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (key.startsWith("flydb.") && !ConfigLoader.knownKeys().contains(key) && !key.startsWith("flydb.placeholders."))
                throw new com.flydb.core.exception.FlydbException(com.flydb.core.exception.ErrorCode.UNKNOWN_CONFIG_KEY, key);
            if (!entry.getValue().isNull() && !entry.getValue().isTextual()) throw new WebException(400, "INVALID_REQUEST", "Configuration values must be strings");
            edits.put(key, entry.getValue().isNull() ? null : entry.getValue().asText());
        });
        ConfigurationDocument document = document(profile);
        Map<String, String> merged = new LinkedHashMap<String, String>(document.values());
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            if (edit.getValue() == null) merged.remove(edit.getKey()); else merged.put(edit.getKey(), edit.getValue());
        }
        ConfigLoader.validateDocumentValues(merged, Paths.get(profile.path("configPath").asText()));
        document.save(edits, ProfileStore.required(input, "revision"));
        return read(profile);
    }
    ConfigurationDocument document(ObjectNode profile) throws IOException {
        return ConfigurationDocument.read(Paths.get(profile.path("configPath").asText()));
    }
    String binding(ObjectNode profile, CliConfiguration configuration) throws IOException {
        // Include exact effective secrets in an opaque digest, never in the returned snapshot.
        TreeMap<String, String> stable = new TreeMap<String, String>(configuration.values());
        stable.put("@working", profile.path("workingDirectory").asText());
        stable.put("@config", profile.path("configPath").asText());
        stable.put("@revision", document(profile).revision());
        stable.put("@drivers", profile.path("driversDirectory").asText(installation.resolve("drivers").toString()));
        return ConfigurationDocument.digest(StateJson.MAPPER.writeValueAsBytes(stable));
    }
    Path drivers(ObjectNode profile) { return Paths.get(profile.path("driversDirectory").asText(installation.resolve("drivers").toString())); }
    private ObjectNode visible(Map<String, String> values, ArrayNode secretKeys, boolean editable) {
        ObjectNode result = StateJson.object();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            boolean reference = value != null && value.startsWith("${env:") && value.endsWith("}");
            if (key.matches(".*(?:password|secret|token|credential).*" ) && !key.endsWith(".file") && !reference) {
                secretKeys.add(entry.getKey());
                // Absence in the editable map means keep unchanged; never submit a mask as a password.
                continue;
            }
            String redacted = SecretRedactor.redact(value);
            if (editable && !Objects.equals(value, redacted)) secretKeys.add(entry.getKey());
            else result.put(entry.getKey(), redacted);
        }
        return result;
    }
}
