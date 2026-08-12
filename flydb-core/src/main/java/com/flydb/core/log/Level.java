package com.flydb.core.log;

/**
 * 日志级别（有序，数值越大优先级越高）。包私有——仅 log 包内部使用级别过滤逻辑。
 */
enum Level {

    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3);

    private final int severity;

    Level(int severity) {
        this.severity = severity;
    }

    /** 当前级别是否达到给定阈值（>= 阈值才输出）。 */
    boolean atLeast(Level threshold) {
        return this.severity >= threshold.severity;
    }
}
