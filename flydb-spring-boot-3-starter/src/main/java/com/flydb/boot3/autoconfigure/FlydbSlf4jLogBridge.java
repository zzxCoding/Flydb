package com.flydb.boot3.autoconfigure;

import com.flydb.core.log.Log;
import com.flydb.core.log.LogCreator;
import com.flydb.core.log.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

/** 将 flydb-core 的零依赖日志接口桥接到应用的 SLF4J。 */
public class FlydbSlf4jLogBridge implements InitializingBean {

    @Override
    public void afterPropertiesSet() {
        LogFactory.setLogCreator(new Slf4jLogCreator());
    }

    private static final class Slf4jLogCreator implements LogCreator {
        @Override
        public Log createLog(Class<?> clazz) {
            return new Slf4jLog(LoggerFactory.getLogger(clazz));
        }
    }

    private static final class Slf4jLog implements Log {
        private final Logger logger;

        private Slf4jLog(Logger logger) {
            this.logger = logger;
        }

        @Override public void debug(String message) { logger.debug(message); }
        @Override public void info(String message) { logger.info(message); }
        @Override public void warn(String message) { logger.warn(message); }
        @Override public void error(String message, Throwable t) { logger.error(message, t); }
    }
}
