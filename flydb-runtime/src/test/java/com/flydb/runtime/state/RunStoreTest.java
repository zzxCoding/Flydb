package com.flydb.runtime.state;

import java.nio.file.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flydb.core.api.ExecutionEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RunStoreTest {
    @TempDir Path state;
    @Test void summaryCacheTracksReplacementAndDoesNotCacheLiveness() throws Exception {
        RunStore store = new RunStore(state);
        String id;
        try (RunStore.Writer writer = store.start(StateJson.object().put("command", "plan"))) {
            id = writer.id();
            assertThat(store.listSummaries(1).get(0).path("status").asText()).isEqualTo("RUNNING");
            writer.finish("SUCCEEDED", StateJson.object().put("planId", "first"), "NOT_RUN");
        }
        assertThat(store.listSummaries(1).get(0).path("status").asText()).isEqualTo("SUCCEEDED");
        ObjectNode changed = store.read(id).put("status", "FAILED");
        StateJson.write(state.resolve("runs").resolve(id).resolve("summary.json"), changed);
        assertThat(store.listSummaries(1).get(0).path("status").asText()).isEqualTo("FAILED");
        changed.put("status", "RUNNING");
        StateJson.write(state.resolve("runs").resolve(id).resolve("summary.json"), changed);
        assertThat(store.listSummaries(1).get(0).path("recovery").asText()).isEqualTo("NO_TERMINAL_RESULT");
    }
    @Test void malformedRecordsStillHaveUnknownOutcomes() throws Exception {
        RunStore store = new RunStore(state);
        String id;
        try (RunStore.Writer writer = store.start(StateJson.object())) { id = writer.id(); }
        Path file = state.resolve("runs").resolve(id).resolve("summary.json");
        for (String invalid : new String[] { "{", "[]", "{\"status\":\"FAILED\",\"status\":\"SUCCEEDED\"}" }) {
            Files.write(file, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ObjectNode record = store.list(1).get(0);
            assertThat(record.path("status").asText()).isEqualTo("UNKNOWN");
            assertThat(record.path("recovery").asText()).isEqualTo("UNREADABLE_RECORD");
        }
    }
    @Test void readsLargePreviewWrittenByTheSameStoreWithoutLosingOutcome() throws Exception {
        RunStore store = new RunStore(state);
        char[] chars = new char[9 * 1024 * 1024];
        java.util.Arrays.fill(chars, 'x');
        String sql = new String(chars);
        String id;
        try (RunStore.Writer writer = store.start(StateJson.object().put("command", "plan"))) {
            id = writer.id();
            writer.finish("SUCCEEDED", StateJson.object().put("sql", sql), "NOT_RUN");
        }
        assertThat(Files.size(state.resolve("runs").resolve(id).resolve("summary.json"))).isGreaterThan(8 * 1024 * 1024);
        ObjectNode record = new RunStore(state).list(1).get(0);
        assertThat(record.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(record.path("result").path("sql").asText()).isEqualTo(sql);
        assertThat(store.read(id)).isEqualTo(record);
        assertThatThrownBy(() -> StateJson.read(state.resolve("runs").resolve(id).resolve("summary.json")))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("8 MiB");
    }

    @Test void persistsOrderedRedactedEventsAndDetectsCrossInstanceLiveness() throws Exception {
        RunStore store = new RunStore(state);
        String id;
        try (RunStore.Writer writer = store.start(StateJson.object().put("source", "CLI")
                .put("target", "jdbc:postgresql://user:synthetic-secret@localhost/db"), "synthetic-secret")) {
            id = writer.id();
            writer.onEvent(ExecutionEvent.progress(ExecutionEvent.Type.SQL_PROGRESS, "V1__data.sql", 3, 10));
            assertThat(new RunStore(state).read(id).path("status").asText()).isEqualTo("RUNNING");
            writer.finish("FAILED", StateJson.object().put("detail", "bad synthetic-secret response"), "NOT_RUN");
        }
        ObjectNode summary = store.read(id);
        assertThat(summary.path("status").asText()).isEqualTo("FAILED");
        assertThat(summary.toString()).doesNotContain("synthetic-secret");
        assertThat(store.events(id, 1, 50)).hasSize(2);
        assertThat(store.events(id, 1, 50).get(0).path("sequence").asLong()).isEqualTo(2);
    }
    @Test void lostTerminalMarkerDoesNotBecomeSuccessOrGetReplayed() throws Exception {
        RunStore store = new RunStore(state);
        RunStore.Writer writer = store.start(StateJson.object(), "");
        String id = writer.id(); writer.close();
        Path file = state.resolve("runs").resolve(id).resolve("summary.json");
        ObjectNode summary = StateJson.read(file); summary.put("status", "RUNNING"); StateJson.write(file, summary);
        assertThat(new RunStore(state).read(id).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(StateJson.read(file).path("status").asText()).isEqualTo("RUNNING");
    }

    @Test void redactsSensitivePlaceholderLiteralsInsideExpandedSql() throws Exception {
        RunStore store = new RunStore(state);
        String id;
        try (RunStore.Writer writer = store.start(StateJson.object(), "db-secret", "placeholder-secret")) {
            id = writer.id();
            writer.finish("SUCCEEDED", StateJson.object().put("sql", "SELECT 'placeholder-secret', 'db-secret'"), "PASSED");
        }
        assertThat(store.read(id).toString()).doesNotContain("placeholder-secret", "db-secret");
    }
}
