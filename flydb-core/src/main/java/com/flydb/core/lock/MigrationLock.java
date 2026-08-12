package com.flydb.core.lock;

/** 命令级数据库迁移互斥锁（设计 04 §2）。 */
public interface MigrationLock extends AutoCloseable {

    void acquire();

    void release();

    @Override
    default void close() {
        release();
    }
}
