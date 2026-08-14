package com.flydb.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** migrate/undo 预演的不可变结果；产生结果的过程不建表、不加锁、不记账。 */
public final class DryRunResult {
    private final List<DryRunMigration> migrations;

    public DryRunResult(List<DryRunMigration> migrations) {
        if (migrations == null) throw new NullPointerException("migrations");
        this.migrations = Collections.unmodifiableList(
                new ArrayList<DryRunMigration>(migrations));
    }

    public List<DryRunMigration> migrations() { return migrations; }
}
