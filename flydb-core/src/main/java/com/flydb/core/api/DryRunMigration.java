package com.flydb.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.flydb.core.migration.MigrationType;

/** dry-run 中一份将要执行的迁移脚本及其解析结果。 */
public final class DryRunMigration {
    private final String script;
    private final MigrationType type;
    private final List<DryRunStatement> statements;

    public DryRunMigration(String script, MigrationType type, List<DryRunStatement> statements) {
        if (script == null) throw new NullPointerException("script");
        if (type == null) throw new NullPointerException("type");
        if (statements == null) throw new NullPointerException("statements");
        this.script = script;
        this.type = type;
        this.statements = Collections.unmodifiableList(
                new ArrayList<DryRunStatement>(statements));
    }

    public String script() { return script; }
    public MigrationType type() { return type; }
    public List<DryRunStatement> statements() { return statements; }
}
