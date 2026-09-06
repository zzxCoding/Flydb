package com.flydb.core.api;

/** Optional observation only. Implementations must return promptly and never control migration. */
@FunctionalInterface
public interface ExecutionObserver {
    ExecutionObserver NONE = event -> { };
    void onEvent(ExecutionEvent event);

    /** An unavailable observer must not change a database transaction's outcome. */
    static void notify(ExecutionObserver observer, ExecutionEvent event) {
        try { observer.onEvent(event); } catch (RuntimeException ignored) { }
    }
}
