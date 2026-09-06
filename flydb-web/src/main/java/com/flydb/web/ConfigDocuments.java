package com.flydb.web;

import java.io.IOException;
import java.util.*;
import java.nio.file.Paths;
import com.flydb.runtime.init.InitScaffolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flydb.runtime.config.ConfigLoader;
import com.flydb.runtime.config.ConfigurationDocument;
import com.flydb.runtime.state.StateJson;

/** Explicit file-editor operations. Pure conversion never reads credentials from the environment. */
final class ConfigDocuments {
    static ObjectNode init(ObjectNode input) throws IOException {
        for (String key : Arrays.asList("workingDirectory", "url"))
            if (!input.path(key).isTextual() || input.path(key).asText().trim().isEmpty())
                throw new WebException(400, "INVALID_REQUEST", key + " is required");
        String content = new InitScaffolder().configurationDraft(
                Paths.get(input.path("workingDirectory").asText()).toAbsolutePath().normalize(),
                input.path("url").asText(), input.path("user").asText(),
                input.path("driver").asText(), input.path("databaseType").asText());
        return response(ConfigurationDocument.parse(content));
    }
    static ConfigurationDocument parse(ObjectNode input) throws IOException {
        if (!input.path("content").isTextual()) throw new WebException(400, "INVALID_REQUEST", "Expected configuration content");
        ConfigurationDocument document;
        try { document = ConfigurationDocument.parse(input.path("content").asText()); }
        catch (ConfigurationDocument.InvalidSyntax e) { throw new WebException(400, "CONFIG_SYNTAX", "Line " + e.line() + ": invalid Properties escape"); }
        catch (IOException e) { throw new WebException(400, "CONFIG_SYNTAX", "Invalid Properties syntax or document larger than 2 MiB"); }
        if (input.has("values")) document = document.withEdits(edits(input.path("values")));
        if (input.path("validate").asBoolean(true)) ConfigLoader.validateDocumentValues(document.values());
        return document;
    }
    static Map<String, String> edits(JsonNode values) {
        if (!values.isObject() || values.size() > 200) throw new WebException(400, "INVALID_REQUEST", "Expected at most 200 fields");
        Map<String, String> result = new LinkedHashMap<String, String>();
        values.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isNull() && !entry.getValue().isTextual())
                throw new WebException(400, "INVALID_REQUEST", "Configuration values must be strings");
            result.put(entry.getKey(), entry.getValue().isNull() ? null : entry.getValue().asText());
        });
        return result;
    }
    static ObjectNode response(ConfigurationDocument document) {
        ObjectNode result = StateJson.object().put("content", document.content()).put("revision", document.revision());
        result.set("values", StateJson.MAPPER.valueToTree(document.values()));
        return result;
    }
}
