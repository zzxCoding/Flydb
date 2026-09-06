package com.flydb.web;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.flydb.core.exception.FlydbException;
import com.flydb.runtime.config.*;
import com.flydb.runtime.output.SecretRedactor;
import com.flydb.runtime.state.*;

/** Optional loopback GUI. Browser bootstrap is automatic; there is no user/login/role model. */
public final class WebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService httpThreads = new ThreadPoolExecutor(20, 20, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(64), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService requestDeadlines = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "flydb-web-request-deadline"); thread.setDaemon(true); return thread;
    });
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger streams = new AtomicInteger();
    private final ProfileStore profiles;
    private final ConfigurationService configurations;
    private final RunStore runs;
    private final OperationService operations;
    private final Path working, state, installation;
    private final String version, startupKey = random(), browserKey = random();
    private volatile boolean closed;

    public WebServer(Path state, Path working, Path installation, Map<String, String> environment,
                     int port, String version) throws IOException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Port must be 0–65535");
        this.state = state.toAbsolutePath().normalize(); this.working = working; this.installation = installation; this.version = version;
        this.profiles = new ProfileStore(this.state); this.configurations = new ConfigurationService(installation, environment);
        this.runs = new RunStore(this.state); this.operations = new OperationService(profiles, configurations, runs);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        server.setExecutor(httpThreads); server.createContext("/", this::handle);
    }
    public void importConfiguration(Path config) throws IOException {
        profiles.create(StateJson.object().put("mode", "import").put("configPath", config.toString())
                .put("workingDirectory", working.toString()));
    }
    public URI uri() { return URI.create(origin() + "/#key=" + startupKey); }
    public void start() throws IOException {
        if (WebServer.class.getResource("/web/index.html") == null) throw new IOException(
                "Web assets are missing. Build flydb-web/frontend before packaging the CLI.");
        server.start();
    }
    public void await() throws InterruptedException { stopped.await(); }
    private String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            validateOrigin(exchange);
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/api/")) { staticFile(exchange, path); return; }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("/api/session".equals(path)) { session(exchange); return; }
            if (!hasCookie(exchange)) throw new WebException(401, "SESSION_REQUIRED", "Reopen the address printed by flydb web");
            if (path.matches("/api/runs/[0-9a-f-]+/events")) { events(exchange, path.split("/")[3]); return; }
            json(exchange, 200, route(exchange, path.substring(4)));
        } catch (WebException e) { fail(exchange, e.status, e.code, e.getMessage()); }
        catch (ConfigurationDocument.Conflict e) { fail(exchange, 409, "CONFIG_CONFLICT", "Configuration changed outside the workbench"); }
        catch (FileAlreadyExistsException e) { fail(exchange, 409, "FILE_EXISTS", "Destination already exists"); }
        catch (NoSuchFileException | InvalidPathException e) { fail(exchange, 400, "INVALID_PATH", "Path does not exist or is invalid"); }
        catch (FlydbException e) { fail(exchange, e.errorCode() == com.flydb.core.exception.ErrorCode.INIT_TARGET_EXISTS ? 409 : 400, e.errorCode().code(), SecretRedactor.redact(e.detail())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { fail(exchange, 400, "INVALID_REQUEST", "Invalid JSON request"); }
        catch (Exception e) { fail(exchange, 500, "REQUEST_FAILED", "The local operation could not be completed; check the selected files and configuration"); }
        finally { exchange.close(); }
    }
    private JsonNode route(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/bootstrap".equals(path)) return bootstrap();
        if ("GET".equals(method) && "/files".equals(path)) return LocalFiles.browse(query(exchange).getOrDefault("path", working.toString()));
        if ("GET".equals(method) && "/discover".equals(path)) return LocalFiles.discover(query(exchange).getOrDefault("path", working.toString()));
        if ("POST".equals(method) && "/profiles".equals(path)) return profiles.create(body(exchange));
        if ("POST".equals(method) && "/config/init".equals(path)) return ConfigDocuments.init(body(exchange));
        if ("POST".equals(method) && "/config/document".equals(path)) return ConfigDocuments.response(ConfigDocuments.parse(body(exchange)));
        String[] parts = path.split("/");
        if (parts.length >= 3 && "profiles".equals(parts[1])) {
            String id = parts[2];
            if (parts.length == 3 && "PUT".equals(method)) return profiles.update(id, body(exchange), false);
            if (parts.length == 3 && "DELETE".equals(method)) return profiles.update(id, body(exchange), true);
            if (parts.length == 4 && "config".equals(parts[3])) {
                if ("GET".equals(method)) return configurations.read(profiles.get(id));
                if ("PUT".equals(method)) return configurations.save(profiles.get(id), body(exchange));
            }
            if (parts.length == 4 && "document".equals(parts[3]) && "GET".equals(method))
                return ConfigDocuments.response(configurations.document(profiles.get(id)));
            if (parts.length == 4 && "actions".equals(parts[3]) && "POST".equals(method)) return operations.submit(id, body(exchange));
        }
        if (parts.length == 3 && "runs".equals(parts[1]) && "GET".equals(method)) return runs.read(parts[2]);
        throw new WebException(404, "NOT_FOUND", "Unknown workbench endpoint");
    }
    private ObjectNode bootstrap() throws IOException {
        ObjectNode result = StateJson.object().put("version", version).put("initialDirectory", working.toString())
                .put("stateDirectory", state.toString()).put("driversDirectory", installation.resolve("drivers").toString());
        result.set("profiles", profiles.list()); result.set("runs", StateJson.MAPPER.valueToTree(runs.list(200)));
        result.set("knownKeys", StateJson.MAPPER.valueToTree(ConfigLoader.knownKeys()));
        return result;
    }
    private void validateOrigin(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (!("127.0.0.1:" + server.getAddress().getPort()).equals(host)) throw new WebException(403, "LOCAL_ONLY", "Use the printed loopback address");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if ((origin != null && !origin().equals(origin)) || "cross-site".equals(site))
            throw new WebException(403, "CROSS_ORIGIN", "Cross-origin access is unavailable");
    }
    private void session(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) throw new WebException(405, "METHOD_NOT_ALLOWED", "POST required");
        ObjectNode input = body(exchange);
        if (input.has("key")) {
            if (!same(startupKey, input.path("key").asText()))
                throw new WebException(401, "SESSION_REQUIRED", "Reopen the local workbench and retry");
        } else if (!origin().equals(exchange.getRequestHeaders().getFirst("Origin"))) {
            // A plain local URL must work in a fresh browser. Cross-site pages cannot
            // make this same-origin JSON POST; navigation/GET never establishes it.
            throw new WebException(403, "CROSS_ORIGIN", "Browser bootstrap requires the local page origin");
        }
        exchange.getResponseHeaders().add("Set-Cookie", "flydb=" + browserKey + "; Path=/; HttpOnly; SameSite=Strict");
        json(exchange, 200, StateJson.object().put("ready", true));
    }
    private boolean hasCookie(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return false;
        for (String part : cookie.split(";")) if (part.trim().startsWith("flydb=")) return same(browserKey, part.trim().substring(6));
        return false;
    }
    private ObjectNode body(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))
            throw new WebException(415, "JSON_REQUIRED", "application/json required");
        byte[] bytes;
        ScheduledFuture<?> deadline = requestDeadlines.schedule(exchange::close, 15, TimeUnit.SECONDS);
        try { bytes = readBounded(exchange.getRequestBody(), 2 * 1024 * 1024); }
        finally { deadline.cancel(false); }
        JsonNode node = StateJson.MAPPER.readTree(bytes);
        if (node == null || !node.isObject()) throw new WebException(400, "INVALID_REQUEST", "Expected JSON object");
        return (ObjectNode) node;
    }
    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod()))
            throw new WebException(405, "METHOD_NOT_ALLOWED", "GET required");
        if ("/".equals(path)) path = "/index.html";
        if (path.contains("..") || path.contains("\\") || !path.matches("/[a-zA-Z0-9_./-]+")) throw new WebException(404, "NOT_FOUND", "Resource not found");
        try (InputStream resource = WebServer.class.getResourceAsStream("/web" + path)) {
            if (resource == null) throw new WebException(404, "NOT_FOUND", "Resource not found");
            String type = path.endsWith(".js") ? "application/javascript" : path.endsWith(".css") ? "text/css"
                    : path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".png") ? "image/png"
                    : path.endsWith(".txt") ? "text/plain" : "text/html";
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            byte[] bytes = readBounded(resource, 8 * 1024 * 1024);
            if ("HEAD".equals(exchange.getRequestMethod())) exchange.sendResponseHeaders(200, -1);
            else { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
        }
    }
    private void events(HttpExchange exchange, String id) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) throw new WebException(405, "METHOD_NOT_ALLOWED", "GET required");
        runs.read(id);
        if (streams.incrementAndGet() > 8) { streams.decrementAndGet(); throw new WebException(429, "BUSY", "Too many live views"); }
        try {
            String last = exchange.getRequestHeaders().getFirst("Last-Event-ID");
            if (last != null && !last.matches("[0-9]{1,15}")) throw new WebException(400, "INVALID_REQUEST", "Invalid event sequence");
            long after = last == null ? 0 : Long.parseLong(last);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            while (!closed) {
                List<JsonNode> available = runs.events(id, after, 100);
                for (JsonNode event : available) {
                    after = event.path("sequence").asLong();
                    out.write(("id: " + after + "\ndata: " + event.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                }
                ObjectNode current = runs.read(id);
                if (!"RUNNING".equals(current.path("status").asText())
                        && (after >= current.path("sequence").asLong() || available.isEmpty())) {
                    out.write("event: finished\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); return;
                }
                out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        } finally { streams.decrementAndGet(); }
    }
    private static Map<String, String> query(HttpExchange exchange) throws UnsupportedEncodingException {
        Map<String, String> values = new HashMap<String, String>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            if (pair.length == 2) values.put(URLDecoder.decode(pair[0], "UTF-8"), URLDecoder.decode(pair[1], "UTF-8"));
        }
        return values;
    }
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new WebException(413, "REQUEST_TOO_LARGE", "Input is too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static void fail(HttpExchange exchange, int status, String code, String detail) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        json(exchange, status, StateJson.object().put("code", code).put("detail", detail));
    }
    private static void json(HttpExchange exchange, int status, JsonNode value) throws IOException {
        byte[] bytes = StateJson.MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static String random() {
        byte[] value = new byte[32]; new SecureRandom().nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    private static boolean same(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
    @Override public void close() {
        if (closed) return; closed = true; server.stop(0); httpThreads.shutdownNow(); requestDeadlines.shutdownNow(); operations.close(); stopped.countDown();
    }
}
