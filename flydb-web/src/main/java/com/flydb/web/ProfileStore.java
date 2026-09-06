package com.flydb.web;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.flydb.runtime.config.ConfigurationDocument;
import com.flydb.runtime.init.InitScaffolder;
import com.flydb.runtime.state.StateJson;

/** Registration points at original config files; display metadata has no permission semantics. */
final class ProfileStore {
    private final Path state;
    ProfileStore(Path state) throws IOException {
        this.state = state.toAbsolutePath().normalize();
        Files.createDirectories(state); StateJson.restrict(state, true);
    }
    synchronized ArrayNode list() throws IOException {
        Path file = state.resolve("profiles.json");
        if (!Files.exists(file)) return StateJson.array();
        JsonNode profiles = StateJson.read(file).path("profiles");
        if (!profiles.isArray()) throw new IOException("Invalid profile registry");
        return ((ArrayNode) profiles).deepCopy();
    }
    ObjectNode get(String id) throws IOException {
        for (JsonNode profile : list()) if (id.equals(profile.path("id").asText())) return (ObjectNode) profile;
        throw new WebException(404, "PROFILE_NOT_FOUND", "Configuration is no longer registered");
    }
    synchronized ArrayNode create(ObjectNode input) throws IOException {
        try (FileChannel channel = FileChannel.open(state.resolve("profiles.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            ArrayNode all = list(), added = StateJson.array();
            String mode = input.path("mode").asText("import");
            String requested = required(input, "workingDirectory");
            ConfigurationDocument creation = null;
            if ("create".equals(mode) && input.has("content")) {
                creation = ConfigDocuments.parse(input.deepCopy().put("validate", true));
                String url = creation.values().get("flydb.url");
                if (url == null || url.trim().isEmpty()) throw new WebException(400, "INVALID_REQUEST", "flydb.url is required");
            }
            // Check display metadata and the external driver path before creating any files.
            bounded(input.path("name").asText(), 120); bounded(input.path("environment").asText(), 60);
            bounded(input.path("group").asText(), 80);
            if (input.hasNonNull("driversDirectory")) directory(required(input, "driversDirectory"));
            if ("create".equals(mode)) Files.createDirectories(Paths.get(requested).toAbsolutePath().normalize());
            Path working = directory(requested);
            if ("create".equals(mode)) {
                if (creation == null) {
                    String url = required(input, "url");
                    new InitScaffolder().create(working, url, input.path("user").asText(), null, null);
                } else {
                    new InitScaffolder().create(working, creation.content());
                    StateJson.restrict(working.resolve("flydb.conf"), false);
                }
                input.put("configPath", working.resolve("flydb.conf").toString());
            } else if ("duplicate".equals(mode)) {
                ObjectNode original = get(required(input, "sourceId"));
                Path source = Paths.get(original.path("configPath").asText());
                Path target = Paths.get(required(input, "configPath")).toAbsolutePath().normalize();
                if (!Files.isDirectory(target.getParent())) throw new WebException(400, "INVALID_PATH", "Destination directory is missing");
                Files.copy(source, target); // CREATE_NEW semantics: never overwrite a user's file.
                StateJson.restrict(target, false);
            } else if (!"import".equals(mode) && !"discover".equals(mode)) {
                throw new WebException(400, "INVALID_REQUEST", "Unknown profile operation");
            }
            if ("discover".equals(mode)) {
                JsonNode paths = input.path("paths");
                if (!paths.isArray() || paths.size() > 200) throw new WebException(400, "INVALID_REQUEST", "Select at most 200 configurations");
                // Validate the entire selection before registering anything.
                for (JsonNode path : paths) ConfigurationDocument.read(Paths.get(path.asText()));
                for (JsonNode path : paths) added.add(register(all, Paths.get(path.asText()), input, working, true));
            } else added.add(register(all, Paths.get(required(input, "configPath")), input, working, false));
            save(all); return added;
        }
    }
    private ObjectNode register(ArrayNode all, Path file, ObjectNode input, Path working, boolean multiple) throws IOException {
        Path actual = file.toRealPath(); ConfigurationDocument.read(actual);
        for (JsonNode existing : all) if (actual.toString().equals(existing.path("configPath").asText())) return (ObjectNode) existing;
        String fallback = actual.getFileName().toString();
        if ("flydb.conf".equals(fallback) && actual.getParent().getFileName() != null) fallback = actual.getParent().getFileName().toString();
        String name = multiple ? fallback : input.path("name").asText().trim();
        ObjectNode profile = StateJson.object().put("id", UUID.randomUUID().toString())
                .put("name", name.isEmpty() ? fallback : bounded(name, 120))
                .put("environment", bounded(input.path("environment").asText(), 60))
                .put("group", bounded(input.path("group").asText(), 80))
                .put("configPath", actual.toString()).put("workingDirectory", working.toString());
        if (input.hasNonNull("driversDirectory")) profile.put("driversDirectory", directory(required(input, "driversDirectory")).toString());
        all.add(profile); return profile;
    }
    synchronized ObjectNode update(String id, ObjectNode input, boolean remove) throws IOException {
        try (FileChannel channel = FileChannel.open(state.resolve("profiles.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            ArrayNode all = list();
            for (int i = 0; i < all.size(); i++) {
                ObjectNode profile = (ObjectNode) all.get(i);
                if (!id.equals(profile.path("id").asText())) continue;
                if (remove) all.remove(i);
                else {
                    profile.put("name", bounded(required(input, "name"), 120));
                    profile.put("group", bounded(input.path("group").asText(), 80));
                    profile.put("environment", bounded(input.path("environment").asText(), 60));
                    profile.put("workingDirectory", directory(required(input, "workingDirectory")).toString());
                    if (input.hasNonNull("driversDirectory")) profile.put("driversDirectory", directory(required(input, "driversDirectory")).toString());
                }
                save(all); return profile;
            }
            throw new WebException(404, "PROFILE_NOT_FOUND", "Configuration is no longer registered");
        }
    }
    private void save(ArrayNode all) throws IOException {
        StateJson.write(state.resolve("profiles.json"), StateJson.object().set("profiles", all));
    }
    static Path directory(String path) throws IOException {
        if (path.trim().isEmpty()) throw new WebException(400, "INVALID_PATH", "Choose a working directory");
        Path actual = Paths.get(path).toRealPath();
        if (!Files.isDirectory(actual)) throw new WebException(400, "INVALID_PATH", "Expected a directory");
        return actual;
    }
    static String required(ObjectNode input, String key) {
        JsonNode value = input.path(key);
        if (!value.isTextual() || value.asText().trim().isEmpty()) throw new WebException(400, "INVALID_REQUEST", "Missing " + key);
        return value.asText().trim();
    }
    private static String bounded(String value, int size) {
        if (value.length() > size) throw new WebException(400, "INVALID_REQUEST", "Display value is too long");
        return value;
    }
}
