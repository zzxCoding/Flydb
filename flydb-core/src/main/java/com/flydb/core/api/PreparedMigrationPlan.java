package com.flydb.core.api;

/** A dry-run with target binding. The original PlanArtifact algorithm remains unchanged. */
public final class PreparedMigrationPlan {
    private final DryRunResult preview;
    private final String targetBinding;
    private final String timestamp;

    public PreparedMigrationPlan(DryRunResult preview, String targetBinding, String timestamp) {
        this.preview = java.util.Objects.requireNonNull(preview, "preview");
        this.targetBinding = java.util.Objects.requireNonNull(targetBinding, "targetBinding");
        this.timestamp = java.util.Objects.requireNonNull(timestamp, "timestamp");
    }
    public DryRunResult preview() { return preview; }
    public String targetBinding() { return targetBinding; }
    public String timestamp() { return timestamp; }
}
