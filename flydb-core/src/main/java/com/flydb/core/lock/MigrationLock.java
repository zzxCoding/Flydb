package com.flydb.core.lock;

import java.util.Collections;
import java.util.List;

/** 命令级数据库迁移互斥锁（设计 04 §2）。 */
public interface MigrationLock extends AutoCloseable {

    void acquire();

    void release();

    /** 锁能力降级等非致命提示。 */
    default List<String> warnings() {
        return Collections.emptyList();
    }

    @Override
    default void close() {
        release();
    }
}
