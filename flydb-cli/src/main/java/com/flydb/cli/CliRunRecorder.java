package com.flydb.cli;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flydb.core.api.ExecutionEvent;
import com.flydb.core.api.ExecutionObserver;
import com.flydb.core.exception.FlydbException;
import com.flydb.runtime.config.CliConfiguration;
import com.flydb.runtime.config.ConfigLoader;
import com.flydb.runtime.config.ConfigurationDocument;
import com.flydb.runtime.output.SecretRedactor;
import com.flydb.runtime.state.*;

/** Observation does not alter terminal output, domain outcomes, or the need to start a Web server. */
final class CliRunRecorder implements AutoCloseable, ExecutionObserver {
    private RunStore.Writer writer;
    private JsonNode result;
    private final PrintWriter err;
    private boolean warning;

    CliRunRecorder(String command, boolean dryRun, CliConfiguration configuration, Path config,
                   Path working, Path installation, Map<String, String> environment, PrintWriter err) {
        this.err = err;
        try {
            Path file = ConfigLoader.locate(config, working, installation);
            ObjectNode metadata = StateJson.object().put("source", "CLI")
                    .put("command", dryRun && "migrate".equals(command) ? "plan" : dryRun && "undo".equals(command) ? "undo-plan" : command)
                    .put("target", SecretRedactor.redact(configuration.url())).put("workingDirectory", working.toString());
            if (file != null) metadata.put("configPath", file.toRealPath().toString())
                    .put("configRevision", ConfigurationDocument.read(file).revision());
            writer = new RunStore(RunStore.defaultDirectory(environment)).start(metadata, configuration.sensitiveValues());
        } catch (IOException | RuntimeException e) { warn(); }
    }
    void result(String json) {
        if (writer == null) return;
        try { result = StateJson.MAPPER.readTree(json); } catch (IOException e) { warn(); }
    }
    void success() { finish("SUCCEEDED", result); }
    void failure(Throwable error) {
        ObjectNode failure = StateJson.object();
        String code = error instanceof FlydbException ? ((FlydbException) error).errorCode().code() : "EXECUTION_FAILED";
        failure.set("error", StateJson.object().put("code", code).put("detail", String.valueOf(error.getMessage())));
        finish("FAILED", failure);
    }
    private void finish(String status, JsonNode result) {
        if (writer == null) return;
        try { writer.finish(status, result, "NOT_RUN"); } catch (IOException e) { warn(); }
    }
    @Override public void onEvent(ExecutionEvent event) { if (writer != null) writer.onEvent(event); }
    @Override public void close() {
        if (writer == null) return;
        try { writer.close(); } catch (IOException e) { warn(); }
    }
    private void warn() {
        if (warning) return; warning = true;
        err.println("Flydb: 本地执行记录不可用；命令结果以终端输出和数据库状态为准。 / Local run recording unavailable.");
    }
}
