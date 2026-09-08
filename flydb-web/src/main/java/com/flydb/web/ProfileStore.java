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
        ObjectNode registry = registry();
        registry.set("profiles", all);
        registry.set("groups", groupNames(registry, all));
        StateJson.write(state.resolve("profiles.json"), registry);
    }
    private ObjectNode registry() throws IOException {
        Path file = state.resolve("profiles.json");
        return Files.exists(file) ? (ObjectNode) StateJson.read(file) : StateJson.object();
    }
    private ArrayNode groupNames(ObjectNode registry, ArrayNode all) {
        Set<String> names = new LinkedHashSet<String>();
        for (JsonNode name : registry.path("groups")) if (name.isTextual() && !name.asText().isEmpty()) names.add(name.asText());
        for (JsonNode profile : all) if (!profile.path("group").asText().isEmpty()) names.add(profile.path("group").asText());
        ArrayNode result = StateJson.array();
        for (String name : names) result.add(name);
        return result;
    }
    synchronized ArrayNode groups() throws IOException { return groupNames(registry(), list()); }

    /** Mutate display organization under the same lock as profile registration, never config files. */
    synchronized ArrayNode organize(ObjectNode input) throws IOException {
        try (FileChannel channel = FileChannel.open(state.resolve("profiles.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            ObjectNode registry = registry();
            ArrayNode all = list();
            List<String> names = new ArrayList<String>();
            for (JsonNode name : groupNames(registry, all)) names.add(name.asText());
            String action = required(input, "action"), name = input.path("name").asText();
            if ("create".equals(action) || "rename".equals(action)) {
                String next = bounded(required(input, "newName"), 80);
                if (names.contains(next)) throw new WebException(409, "GROUP_EXISTS", "Group name already exists");
                if ("create".equals(action)) names.add(next);
                else {
                    if (!names.contains(name)) throw new WebException(404, "GROUP_NOT_FOUND", "Group no longer exists");
                    names.set(names.indexOf(name), next);
                    for (JsonNode p : all) if (name.equals(p.path("group").asText())) ((ObjectNode) p).put("group", next);
                }
            } else if ("delete".equals(action) || "move".equals(action)) {
                if (!names.contains(name)) throw new WebException(404, "GROUP_NOT_FOUND", "Group no longer exists");
                if ("delete".equals(action)) {
                    names.remove(name);
                    for (JsonNode p : all) if (name.equals(p.path("group").asText())) ((ObjectNode) p).put("group", "");
                } else {
                    String before = input.path("before").asText();
                    if (!before.isEmpty() && !names.contains(before)) throw new WebException(404, "GROUP_NOT_FOUND", "Destination group no longer exists");
                    if (!name.equals(before)) { names.remove(name); names.add(before.isEmpty() ? names.size() : names.indexOf(before), name); }
                }
            } else if ("moveProfile".equals(action)) {
                if (!name.isEmpty() && !names.contains(name)) throw new WebException(404, "GROUP_NOT_FOUND", "Destination group no longer exists");
                String id = required(input, "profileId"); boolean found = false;
                for (JsonNode p : all) if (id.equals(p.path("id").asText())) { ((ObjectNode) p).put("group", name); found = true; break; }
                if (!found) throw new WebException(404, "PROFILE_NOT_FOUND", "Configuration is no longer registered");
            } else throw new WebException(400, "INVALID_REQUEST", "Unknown group operation");
            ArrayNode groups = StateJson.array(); for (String value : names) groups.add(value);
            registry.set("profiles", all); registry.set("groups", groups);
            StateJson.write(state.resolve("profiles.json"), registry);
            return groups;
        }
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
