package com.flydb.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * LogFactory 装配点：默认 SystemErrLog，可被适配层（CLI/starter）替换（设计 01 §4）。
 */
class LogFactoryTest {

    @AfterEach
    void reset() {
        // 恢复默认实现，避免测试间互相污染
        LogFactory.setLogCreator(null);
    }

    @Test
    void default_log_is_system_err_implementation() {
        Log log = LogFactory.getLog(LogFactoryTest.class);

        assertThat(log).isInstanceOf(SystemErrLog.class);
    }

    @Test
    void set_log_creator_swaps_implementation() {
        RecordingLogCreator recorder = new RecordingLogCreator();
        LogFactory.setLogCreator(recorder);

        Log log = LogFactory.getLog(String.class);

        assertThat(recorder.requestedClass).isEqualTo(String.class);
        assertThat(log).isSameAs(recorder.lastLog);
    }

    @Test
    void null_creator_resets_to_default() {
        LogFactory.setLogCreator(new RecordingLogCreator());
        LogFactory.setLogCreator(null);

        assertThat(LogFactory.getLog(Object.class)).isInstanceOf(SystemErrLog.class);
    }

    /** 记录 createLog 调用、返回可控 Log 实例的测试替身。 */
    static final class RecordingLogCreator implements LogCreator {
        Class<?> requestedClass;
        Log lastLog = new RecordingLog();

        @Override
        public Log createLog(Class<?> clazz) {
            this.requestedClass = clazz;
            return lastLog;
        }
    }

    static final class RecordingLog implements Log {
        @Override public void debug(String message) { }
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void error(String message, Throwable t) { }
    }
}
