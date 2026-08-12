package com.flydb.core.log;

import java.io.PrintStream;

/**
 * 默认 Log 实现：按级别过滤后写到给定 PrintStream（默认 {@link System#err}）。
 *
 * <p>输出格式：{@code [LEVEL] loggerName: message}；error 额外打印 throwable 堆栈。
 * 阈值来自 {@code flydb.log.level} 系统属性（CLI -X→DEBUG、-q→WARN 映射在该层之外完成）。
 *
 * <p>包私有：外部仅通过 {@link LogFactory} 获取 {@link Log} 实例。
 */
final class SystemErrLog implements Log {

    static final String LEVEL_PROPERTY = "flydb.log.level";

    private final String name;
    private final Level threshold;
    private final PrintStream out;

    SystemErrLog(String name, Level threshold, PrintStream out) {
        this.name = name;
        this.threshold = threshold;
        this.out = out;
    }

    @Override
    public void debug(String message) {
        emit(Level.DEBUG, message, null);
    }

    @Override
    public void info(String message) {
        emit(Level.INFO, message, null);
    }

    @Override
    public void warn(String message) {
        emit(Level.WARN, message, null);
    }

    @Override
    public void error(String message, Throwable t) {
        emit(Level.ERROR, message, t);
    }

    private void emit(Level level, String message, Throwable t) {
        if (!level.atLeast(threshold)) {
            return;
        }
        StringBuilder sb = new StringBuilder(64)
                .append('[').append(level.name()).append("] ")
                .append(name).append(": ").append(message);
        out.println(sb.toString());
        if (t != null) {
            t.printStackTrace(out);
        }
    }

    /** 由系统属性解析阈值；null 或非法值回退 {@link Level#INFO}（防呆、不抛异常）。 */
    static Level resolveLevel(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Level.INFO;
        }
        try {
            return Level.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException notALevel) {
            return Level.INFO;
        }
    }
}
