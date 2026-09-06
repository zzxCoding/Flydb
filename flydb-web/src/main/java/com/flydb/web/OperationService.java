package com.flydb.web;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flydb.core.Flydb;
import com.flydb.core.api.*;
import com.flydb.core.exception.*;
import com.flydb.runtime.RuntimeSession;
import com.flydb.runtime.config.CliConfiguration;
import com.flydb.runtime.output.SecretRedactor;
import com.flydb.runtime.output.json.JsonRenderers;
import com.flydb.runtime.state.*;

/** Bounded background operations survive browser navigation. No arbitrary command or SQL entrypoint. */
final class OperationService implements AutoCloseable {
    private final ProfileStore profiles;
    private final ConfigurationService configs;
    private final RunStore runs;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(24), runnable -> new Thread(runnable, "flydb-web-operation"));
    private final Map<String, Plan> plans = new HashMap<String, Plan>();
    private final Map<String, String> requests = new LinkedHashMap<String, String>();
    private final Set<String> busy = new HashSet<String>();

    OperationService(ProfileStore profiles, ConfigurationService configs, RunStore runs) {
        this.profiles = profiles; this.configs = configs; this.runs = runs;
    }
    synchronized ObjectNode submit(String profileId, ObjectNode input) throws IOException {
        String command = ProfileStore.required(input, "command");
        if (!Arrays.asList("inspect", "validate", "plan", "migrate", "undo-plan", "undo", "baseline", "repair").contains(command))
            throw new WebException(400, "INVALID_REQUEST", "Unsupported operation");
        String requestId = ProfileStore.required(input, "requestId");
        if (!requestId.matches("[a-fA-F0-9-]{36}")) throw new WebException(400, "INVALID_REQUEST", "Expected request UUID");
        String requestKey = profileId + ":" + requestId;
        if (requests.containsKey(requestKey)) return runs.read(requests.get(requestKey));
        // Read back persisted request IDs after a restart; never replay an unknown previous result.
        for (ObjectNode previous : runs.list(1000)) {
            if (requestId.equals(previous.path("requestId").asText()) && profileId.equals(previous.path("profileId").asText()))
                return previous;
        }
        if (busy.contains(profileId)) throw new WebException(409, "BUSY", "An operation is already running");
        ObjectNode profile = profiles.get(profileId);
        if (!configs.document(profile).revision().equals(ProfileStore.required(input, "revision")))
            throw new WebException(409, "PLAN_CHANGED", "Configuration changed; reload before running");
        Map<String, String> overrides = new HashMap<String, String>();
        if (input.hasNonNull("password")) overrides.put("flydb.password", input.path("password").asText());
        if ("baseline".equals(command)) overrides.put("flydb.baseline-version", ProfileStore.required(input, "baselineVersion"));
        if (("baseline".equals(command) || "repair".equals(command)) && !input.path("confirmed").asBoolean())
            throw new WebException(400, "CONFIRM_TARGET", "Confirm this database action");
        CliConfiguration configuration = configs.load(profile, overrides);
        String binding = configs.binding(profile, configuration);
        Plan plan = plan(command, input, profileId, binding);
        ObjectNode metadata = StateJson.object().put("profileId", profileId).put("profileName", profile.path("name").asText())
                .put("configPath", profile.path("configPath").asText()).put("workingDirectory", profile.path("workingDirectory").asText())
                .put("configRevision", configs.document(profile).revision()).put("requestId", requestId)
                .put("source", "WEB").put("command", command).put("target", SecretRedactor.redact(configuration.url()));
        RunStore.Writer writer = runs.start(metadata, configuration.sensitiveValues());
        busy.add(profileId); requests.put(requestKey, writer.id());
        while (requests.size() > 2000) requests.remove(requests.keySet().iterator().next());
        try {
            executor.execute(() -> execute(profile, configuration, binding, command, plan, writer));
        } catch (RejectedExecutionException e) {
            busy.remove(profileId); requests.remove(requestKey);
            writer.finish("FAILED", StateJson.object().put("code", "BUSY"), "NOT_RUN"); writer.close();
            throw new WebException(503, "BUSY", "The workbench operation queue is full");
        }
        return writer.snapshot();
    }
    private Plan plan(String command, ObjectNode input, String profileId, String binding) {
        if (!"migrate".equals(command) && !"undo".equals(command)) return null;
        String id = ProfileStore.required(input, "planId");
        Plan plan = plans.get(id);
        if (plan == null || System.currentTimeMillis() - plan.createdAt > 30 * 60 * 1000L)
            throw new WebException(409, "PLAN_EXPIRED", "Preview again before running");
        if (!plan.profileId.equals(profileId) || !plan.binding.equals(binding)
                || !plan.prepared.preview().direction().equals(command))
            throw new WebException(409, "PLAN_CHANGED", "Configuration or target changed after preview");
        return plan;
    }
    private void execute(ObjectNode profile, CliConfiguration configuration, String binding,
                         String command, Plan plan, RunStore.Writer writer) {
        try (RunStore.Writer recording = writer) {
          try (RuntimeSession session = new RuntimeSession(configuration, configs.drivers(profile),
                     Paths.get(profile.path("workingDirectory").asText()), recording)) {
            // Queued operations use their captured settings, but do not silently use an edited file.
            if (!binding.equals(configs.binding(profile, configuration))) throw new WebException(409, "PLAN_CHANGED", "Configuration changed while queued");
            recording.event(StateJson.object().put("type", "DRIVER_RESOLVED")
                    .put("className", session.driverClass()).put("source", session.driverSource()));
            Flydb flydb = session.flydb();
            ObjectNode result = perform(flydb, configuration, profile, binding, command, plan);
            String verification = "NOT_RUN";
            if (Arrays.asList("migrate", "undo", "baseline", "repair").contains(command)) {
                recording.event(StateJson.object().put("type", "VERIFYING"));
                try {
                    result.set("inspection", info(flydb, configuration));
                    flydb.validate(); verification = "PASSED";
                } catch (RuntimeException e) {
                    verification = "FAILED";
                    result.set("verificationError", error(command, e));
                }
            }
            recording.finish("SUCCEEDED", result, verification);
          } catch (Throwable e) {
              recording.finish(e instanceof Error ? "UNKNOWN" : "FAILED", error(command, e), "NOT_RUN");
          }
        } catch (IOException e) {
            System.err.println("Flydb Web: execution evidence could not be persisted; inspect local run files.");
        } finally {
            synchronized (this) { busy.remove(profile.path("id").asText()); }
        }
    }
    private ObjectNode perform(Flydb flydb, CliConfiguration configuration, ObjectNode profile,
                               String binding, String command, Plan plan) throws IOException {
        switch (command) {
            case "inspect": return info(flydb, configuration);
            case "validate": flydb.validate(); return parse(JsonRenderers.validate());
            case "plan": case "undo-plan": {
                flydb.validate();
                PreparedMigrationPlan prepared = "plan".equals(command) ? flydb.prepareMigrate() : flydb.prepareUndo();
                String id = UUID.randomUUID().toString();
                synchronized (this) {
                    plans.values().removeIf(old -> System.currentTimeMillis() - old.createdAt > 30 * 60 * 1000L);
                    if (plans.size() >= 200) plans.remove(plans.keySet().iterator().next());
                    plans.put(id, new Plan(profile.path("id").asText(), binding, prepared));
                }
                ObjectNode result = parse(JsonRenderers.dryRun(prepared.preview().direction(), prepared.preview(), configuration.password()));
                result.put("planId", id); return result;
            }
            case "migrate": return parse(JsonRenderers.migrate(flydb.migrate(plan.prepared)));
            case "undo": return parse(JsonRenderers.undo(flydb.undo(plan.prepared)));
            case "baseline": flydb.baseline(); return parse(JsonRenderers.baseline(configuration.baselineVersion()));
            case "repair": return parse(JsonRenderers.repair(flydb.repair()));
            default: throw new IllegalArgumentException(command);
        }
    }
    private ObjectNode info(Flydb flydb, CliConfiguration config) throws IOException {
        MigrationInfoService information = flydb.info();
        return parse(JsonRenderers.info(information.databaseName(), config.url(), config.table(), information));
    }
    static ObjectNode error(String command, Throwable error) {
        String code = error instanceof FlydbException ? ((FlydbException) error).errorCode().code()
                : error instanceof WebException ? ((WebException) error).code : "EXECUTION_FAILED";
        String detail = error instanceof FlydbException ? ((FlydbException) error).detail() : String.valueOf(error.getMessage());
        ObjectNode result = StateJson.object().put("command", command).put("status", "error");
        result.set("error", StateJson.object().put("code", code).put("detail", SecretRedactor.redact(detail)));
        return result;
    }
    private static ObjectNode parse(String json) throws IOException { return (ObjectNode) StateJson.MAPPER.readTree(json); }
    @Override public void close() { executor.shutdown(); }
    boolean isRunning() { return executor.getActiveCount() > 0 || !executor.getQueue().isEmpty(); }
    private static final class Plan {
        final String profileId, binding;
        final PreparedMigrationPlan prepared;
        final long createdAt = System.currentTimeMillis();
        Plan(String profileId, String binding, PreparedMigrationPlan prepared) {
            this.profileId = profileId; this.binding = binding; this.prepared = prepared;
        }
    }
}
