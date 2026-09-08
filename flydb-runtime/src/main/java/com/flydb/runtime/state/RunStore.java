package com.flydb.runtime.state;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.filter.FilteringParserDelegate;
import com.fasterxml.jackson.core.filter.TokenFilter;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.flydb.core.api.ExecutionEvent;
import com.flydb.core.api.ExecutionObserver;

/** Cross-process local execution journal. A held OS lock, not a heartbeat, proves liveness. */
public final class RunStore {
    private final Path directory;
    private final Map<String, SummaryCache> summaries = new LinkedHashMap<String, SummaryCache>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SummaryCache> entry) { return size() > 200; }
    };
    public RunStore(Path stateDirectory) throws IOException {
        this.directory = stateDirectory.resolve("runs");
        Files.createDirectories(directory);
        StateJson.restrict(directory, true);
    }
    public static Path defaultDirectory(Map<String, String> environment) {
        String custom = environment.get("FLYDB_WORKBENCH_DIR");
        return custom == null || custom.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".flydb", "workbench")
                : Paths.get(custom).toAbsolutePath().normalize();
    }
    public Writer start(ObjectNode metadata, String... secrets) throws IOException {
        return new Writer(metadata, secrets);
    }
    public ObjectNode read(String id) throws IOException {
        Path run = path(id);
        // Run results contain full migration previews, so they cannot share the
        // small configuration-file size limit. Stream the file to avoid a second
        // full byte-array copy; keep the mapper's JSON structure constraints.
        ObjectNode summary;
        try (InputStream input = Files.newInputStream(run.resolve("summary.json"))) {
            JsonNode node = StateJson.MAPPER.readTree(input);
            if (node == null || !node.isObject()) throw new IOException("Expected JSON object");
            summary = (ObjectNode) node;
        }
        if ("RUNNING".equals(summary.path("status").asText()) && !isLive(run)) {
            summary.put("status", "UNKNOWN");
            summary.put("recovery", "NO_TERMINAL_RESULT");
        }
        return summary;
    }
    public List<ObjectNode> list(int limit) throws IOException {
        return list(limit, false);
    }
    /** SQL-free polling view. Full records remain available through read(id). */
    public List<ObjectNode> listSummaries(int limit) throws IOException {
        return list(limit, true);
    }
    private List<ObjectNode> list(int limit, boolean compact) throws IOException {
        List<Path> paths = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) if (Files.isDirectory(path) && Files.exists(path.resolve("summary.json"))) paths.add(path);
        }
        paths.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        List<ObjectNode> result = new ArrayList<ObjectNode>();
        for (Path path : paths) {
            if (result.size() >= limit) break;
            try { result.add(compact ? readSummary(path.getFileName().toString()) : read(path.getFileName().toString())); }
            catch (IOException e) {
                ObjectNode damaged = StateJson.object().put("id", path.getFileName().toString())
                        .put("status", "UNKNOWN").put("recovery", "UNREADABLE_RECORD");
                result.add(damaged);
            }
        }
        return result;
    }
    private synchronized ObjectNode readSummary(String id) throws IOException {
        Path run = path(id), file = run.resolve("summary.json");
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        SummaryCache cached = summaries.get(id);
        if (cached != null && cached.matches(attributes)) return cached.value.deepCopy();
        final boolean[] omitted = { false };
        TokenFilter filter = new TokenFilter() {
            @Override public TokenFilter includeProperty(String name) {
                if ("statements".equals(name)) { omitted[0] = true; return null; }
                return this;
            }
        };
        ObjectNode summary;
        try (InputStream input = Files.newInputStream(file);
             JsonParser parser = new FilteringParserDelegate(StateJson.MAPPER.getFactory().createParser(input), filter,
                     TokenFilter.Inclusion.INCLUDE_ALL_AND_PATH, true)) {
            JsonNode node = StateJson.MAPPER.readTree(parser);
            if (node == null || !node.isObject()) throw new IOException("Expected JSON object");
            summary = (ObjectNode) node;
        }
        if (omitted[0]) summary.put("detailsOmitted", true);
        if ("RUNNING".equals(summary.path("status").asText())) {
            if (!isLive(run)) summary.put("status", "UNKNOWN").put("recovery", "NO_TERMINAL_RESULT");
        } else if (SummaryCache.same(attributes, Files.readAttributes(file, BasicFileAttributes.class))) {
            summaries.put(id, new SummaryCache(attributes, summary.deepCopy()));
        }
        return summary;
    }
    private static final class SummaryCache {
        final BasicFileAttributes attributes;
        final ObjectNode value;
        SummaryCache(BasicFileAttributes attributes, ObjectNode value) { this.attributes = attributes; this.value = value; }
        boolean matches(BasicFileAttributes other) { return same(attributes, other); }
        static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
            return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()) && Objects.equals(a.fileKey(), b.fileKey());
        }
    }
    public List<JsonNode> events(String id, long after, int limit) throws IOException {
        List<JsonNode> result = new ArrayList<JsonNode>();
        Path events = path(id).resolve("events.jsonl");
        try (BufferedReader reader = Files.newBufferedReader(events, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && result.size() < limit) {
                try {
                    JsonNode event = StateJson.MAPPER.readTree(line);
                    if (event != null && event.path("sequence").asLong() > after) result.add(event);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    // A final partially flushed event is not evidence of a terminal result.
                    break;
                }
            }
        }
        return result;
    }
    private Path path(String id) throws IOException {
        if (!id.matches("[0-9]{13}-[a-f0-9-]{36}")) throw new IOException("RUN_NOT_FOUND");
        return directory.resolve(id);
    }
    private boolean isLive(Path run) throws IOException {
        try (FileChannel channel = FileChannel.open(run.resolve("run.lock"), StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) { return lock == null; }
            catch (OverlappingFileLockException e) { return true; }
        }
    }

    public final class Writer implements AutoCloseable, ExecutionObserver {
        private final String id = String.format(Locale.ROOT, "%013d-", System.currentTimeMillis()) + UUID.randomUUID();
        private final Path run = directory.resolve(id);
        private final ObjectNode summary;
        private final String[] secrets;
        private FileChannel lockChannel;
        private FileLock lock;
        private FileChannel events;
        private long sequence;
        private boolean terminal;
        private boolean closed;
        private IOException recordingError;

        private Writer(ObjectNode metadata, String[] secrets) throws IOException {
            this.secrets = secrets.clone();
            Files.createDirectory(run); StateJson.restrict(run, true);
            try {
            lockChannel = FileChannel.open(run.resolve("run.lock"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            lock = lockChannel.lock();
            events = FileChannel.open(run.resolve("events.jsonl"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            summary = (ObjectNode) StateJson.redact(metadata, secrets);
            summary.put("schemaVersion", 1).put("id", id).put("status", "RUNNING")
                    .put("startedAt", Instant.now().toString()).put("verification", "NOT_RUN");
            append(StateJson.object().put("type", "STARTED"));
            } catch (IOException | RuntimeException e) {
                try { if (events != null) events.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                try { if (lock != null) lock.release(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                try { if (lockChannel != null) lockChannel.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            }
        }
        public String id() { return id; }
        public synchronized ObjectNode snapshot() { return summary.deepCopy(); }

        @Override public synchronized void onEvent(ExecutionEvent observation) {
            ObjectNode event = StateJson.object().put("type", observation.type().name())
                    .put("script", observation.script()).put("confirmed", observation.confirmed())
                    .put("total", observation.total()).put("phase", observation.phase())
                    .put("transaction", observation.transaction()).put("failureStart", observation.failureStart())
                    .put("failureEnd", observation.failureEnd()).put("lineNumber", observation.lineNumber());
            try { append(event); }
            catch (IOException e) { recordingError = e; }
        }
        public synchronized void event(ObjectNode event) throws IOException { append(event); }
        public synchronized void finish(String status, JsonNode result, String verification) throws IOException {
            if (terminal) return;
            summary.put("status", status).put("endedAt", Instant.now().toString()).put("verification", verification);
            if (result != null) summary.set("result", StateJson.redact(result, secrets));
            if (recordingError != null) summary.put("recording", "INCOMPLETE");
            append(StateJson.object().put("type", "FINISHED").put("status", status));
            terminal = true;
        }
        private void append(ObjectNode source) throws IOException {
            if (closed) return;
            ObjectNode event = (ObjectNode) StateJson.redact(source, secrets);
            event.put("schemaVersion", 1).put("runId", id).put("sequence", ++sequence).put("at", Instant.now().toString());
            ByteBuffer buffer = ByteBuffer.wrap((StateJson.MAPPER.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) events.write(buffer);
            events.force(false);
            summary.put("sequence", sequence).put("lastActivityAt", event.path("at").asText());
            if (event.hasNonNull("script")) summary.put("script", event.path("script").asText());
            if ("DRIVER_RESOLVED".equals(event.path("type").asText())) summary.set("driver", event.deepCopy());
            if ("SQL_PROGRESS".equals(event.path("type").asText())) summary.set("progress", event.deepCopy());
            if ("TRANSACTION_RESULT".equals(event.path("type").asText())) summary.set("transactionResult", event.deepCopy());
            StateJson.write(run.resolve("summary.json"), summary);
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            try {
                if (!terminal) finish("UNKNOWN", null, "NOT_RUN");
            } finally {
                closed = true;
                try { events.close(); } finally {
                    try { lock.release(); } finally { lockChannel.close(); }
                }
            }
        }
    }
}
