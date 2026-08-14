package com.flydb.core.lock;

import java.util.Collections;
import java.util.List;

/** 为锁能力降级附加可观察警告的委托锁。 */
public final class WarningMigrationLock implements MigrationLock {

    private final MigrationLock delegate;
    private final List<String> warnings;

    public WarningMigrationLock(MigrationLock delegate, String warning) {
        this.delegate = delegate;
        this.warnings = Collections.singletonList(warning);
    }

    @Override
    public void acquire() {
        delegate.acquire();
    }

    @Override
    public void release() {
        delegate.release();
    }

    @Override
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
