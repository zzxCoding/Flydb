package com.flydb.runtime.state;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flydb.runtime.output.SecretRedactor;

/** Bounded local data serialization. No polymorphic type deserialization. */
public final class StateJson {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private StateJson() { }
    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }
    public static ObjectNode read(Path file) throws IOException {
        if (Files.size(file) > 8 * 1024 * 1024) throw new IOException("State file exceeds 8 MiB");
        JsonNode node = MAPPER.readTree(Files.readAllBytes(file));
        if (node == null || !node.isObject()) throw new IOException("Expected JSON object");
        return (ObjectNode) node;
    }
    public static void write(Path file, JsonNode value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".flydb-state-", ".tmp");
        try {
            restrict(temporary, false);
            Files.write(temporary, MAPPER.writeValueAsBytes(value));
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static void restrict(Path file, boolean directory) throws IOException {
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); }
        catch (UnsupportedOperationException ignored) { }
    }
    public static JsonNode redact(JsonNode node, String... secrets) {
        if (node.isTextual()) {
            String value = SecretRedactor.redact(node.asText());
            for (String secret : secrets) value = SecretRedactor.redactSecret(value, secret);
            return new TextNode(value);
        }
        if (node.isArray()) {
            ArrayNode result = array();
            node.forEach(item -> result.add(redact(item, secrets)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = object();
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                String lowered = key.toLowerCase(java.util.Locale.ROOT);
                if (lowered.matches(".*(?:password|secret|token|credential).*")) result.put(key, "****");
                else result.set(key, redact(entry.getValue(), secrets));
            });
            return result;
        }
        return node.deepCopy();
    }
}
