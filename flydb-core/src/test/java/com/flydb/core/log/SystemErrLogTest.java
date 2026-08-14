package com.flydb.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * SystemErrLog 默认实现：级别过滤与 System.err 输出（设计 01 §4）。
 *
 * <p>core 不依赖任何日志门面；默认实现写 System.err，级别可由 {@code flydb.log.level} 控制
 * （CLI 的 -X 映射 DEBUG、-q 映射 WARN）。测试通过注入 PrintStream 捕获输出。
 */
class SystemErrLogTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    @AfterEach
    void restore() {
        // 防御：测试可能改了系统属性，统一复位
        System.clearProperty("flydb.log.level");
    }

    @Test
    void debug_emitted_at_debug_level() {
        Log log = new SystemErrLog("Test", Level.DEBUG, new PrintStream(captured));

        log.debug("hello");

        assertThat(captured.toString()).contains("[DEBUG]").contains("Test").contains("hello");
    }

    @Test
    void debug_suppressed_at_info_level() {
        Log log = new SystemErrLog("Test", Level.INFO, new PrintStream(captured));

        log.debug("should-not-appear");
        log.info("should-appear");

        String out = captured.toString();
        assertThat(out).doesNotContain("should-not-appear");
        assertThat(out).contains("[INFO]").contains("should-appear");
    }

    @Test
    void warn_and_above_always_emitted_at_warn_level() {
        Log log = new SystemErrLog("Core", Level.WARN, new PrintStream(captured));

        log.info("suppressed");
        log.warn("careful");

        String out = captured.toString();
        assertThat(out).doesNotContain("suppressed");
        assertThat(out).contains("[WARN]").contains("careful");
    }

    @Test
    void error_prints_message_and_stacktrace() {
        Log log = new SystemErrLog("Core", Level.ERROR, new PrintStream(captured));

        log.error("boom", new IllegalStateException("root"));

        String out = captured.toString();
        assertThat(out).contains("[ERROR]").contains("boom");
        assertThat(out).contains("IllegalStateException");
        assertThat(out).contains("root");
    }

    @Test
    void resolves_level_from_system_property_defaulting_to_info() {
        assertThat(SystemErrLog.resolveLevel(null)).isEqualTo(Level.INFO);
        assertThat(SystemErrLog.resolveLevel("DEBUG")).isEqualTo(Level.DEBUG);
        assertThat(SystemErrLog.resolveLevel("warn")).isEqualTo(Level.WARN);
        // 非法值回退到默认 INFO（不抛异常）
        assertThat(SystemErrLog.resolveLevel("nope")).isEqualTo(Level.INFO);
    }
}
