package com.flydb.core.log;

/**
 * Log 工厂的可替换创建点（设计 01 §4）。
 *
 * <p>CLI 与 starter 分别注入各自的实现，把 core 的 {@link Log} 适配到 picocli 控制台或 SLF4J。
 */
public interface LogCreator {

    Log createLog(Class<?> clazz);
}
