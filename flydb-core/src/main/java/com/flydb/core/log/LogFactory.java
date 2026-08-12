package com.flydb.core.log;

/**
 * Log 装配入口（设计 01 §4）。
 *
 * <p>用法：{@code Log log = LogFactory.getLog(MyClass.class);}。
 * 适配层在启动早期调用 {@link #setLogCreator} 替换默认的 System.err 实现；传 null 重置为默认。
 */
public final class LogFactory {

    private static volatile LogCreator creator = new DefaultLogCreator();

    private LogFactory() {
    }

    public static Log getLog(Class<?> clazz) {
        return creator.createLog(clazz);
    }

    /**
     * 替换 Log 创建策略。{@code null} 重置为默认 System.err 实现。
     */
    public static void setLogCreator(LogCreator logCreator) {
        creator = (logCreator == null) ? new DefaultLogCreator() : logCreator;
    }

    /** 默认创建器：读取 flydb.log.level，写 System.err。 */
    private static final class DefaultLogCreator implements LogCreator {
        @Override
        public Log createLog(Class<?> clazz) {
            Level level = SystemErrLog.resolveLevel(System.getProperty(SystemErrLog.LEVEL_PROPERTY));
            return new SystemErrLog(clazz.getName(), level, System.err);
        }
    }
}
