package com.flydb.core.log;

/**
 * 极简日志抽象（设计 01 §4）。
 *
 * <p>core 不依赖 SLF4J（零第三方运行时依赖承诺）。四个级别方法覆盖迁移工具全部输出需求；
 * 适配层（CLI 控制台着色、starter 桥接 SLF4J）通过 {@link LogFactory#setLogCreator} 替换实现，
 * core 对具体后端无感知。
 */
public interface Log {

    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable t);
}
