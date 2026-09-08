package com.flydb.web;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.node.*;
import com.flydb.runtime.state.StateJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProfileStoreTest {
    @TempDir Path temporary;
    @Test void groupsPersistAndRenameDeleteAndMoveOnlyChangeDisplayMetadata() throws Exception {
        Path state = temporary.resolve("state"), config = temporary.resolve("flydb.conf");
        byte[] content = "flydb.url=jdbc:missing:test\n".getBytes(StandardCharsets.UTF_8); Files.write(config, content);
        ProfileStore store = new ProfileStore(state);
        String id = store.create(StateJson.object().put("configPath", config.toString()).put("workingDirectory", temporary.toString()).put("group", "Legacy")).get(0).path("id").asText();
        store.organize(StateJson.object().put("action", "create").put("newName", "Empty"));
        assertThat(new ProfileStore(state).groups().toString()).isEqualTo("[\"Legacy\",\"Empty\"]");
        store.organize(StateJson.object().put("action", "move").put("name", "Empty").put("before", "Legacy"));
        assertThat(store.groups().get(0).asText()).isEqualTo("Empty");
        store.organize(StateJson.object().put("action", "rename").put("name", "Legacy").put("newName", "Production"));
        assertThat(store.get(id).path("group").asText()).isEqualTo("Production");
        store.organize(StateJson.object().put("action", "moveProfile").put("profileId", id).put("name", "Empty"));
        assertThat(store.get(id).path("group").asText()).isEqualTo("Empty");
        store.organize(StateJson.object().put("action", "delete").put("name", "Empty"));
        assertThat(store.get(id).path("group").asText()).isEmpty();
        assertThat(Files.readAllBytes(config)).isEqualTo(content);
        store.update(id, store.get(id).put("name", "Changed"), false);
        assertThat(new ProfileStore(state).groups().toString()).isEqualTo("[\"Production\"]");
        store.update(id, StateJson.object(), true);
        assertThat(store.groups().toString()).isEqualTo("[\"Production\"]");
    }
    @Test void invalidOrStaleOperationsAreAtomicAndLegacyGroupsAreDiscovered() throws Exception {
        Path state = temporary.resolve("state"); Files.createDirectories(state);
        StateJson.write(state.resolve("profiles.json"), StateJson.object().set("profiles", StateJson.array().add(StateJson.object().put("id", "one").put("group", "Legacy"))));
        ProfileStore store = new ProfileStore(state);
        assertThat(store.groups().toString()).isEqualTo("[\"Legacy\"]");
        byte[] before = Files.readAllBytes(state.resolve("profiles.json"));
        for (ObjectNode request : new ObjectNode[] {
            StateJson.object().put("action", "create").put("newName", " Legacy "),
            StateJson.object().put("action", "create").put("newName", " "),
            StateJson.object().put("action", "rename").put("name", "gone").put("newName", "Next"),
            StateJson.object().put("action", "move").put("name", "Legacy").put("before", "gone"),
            StateJson.object().put("action", "moveProfile").put("profileId", "gone").put("name", "Legacy"),
            StateJson.object().put("action", "delete").put("name", "")
        }) {
            assertThatThrownBy(() -> store.organize(request)).isInstanceOf(WebException.class);
            assertThat(Files.readAllBytes(state.resolve("profiles.json"))).isEqualTo(before);
        }
    }
}
