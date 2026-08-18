package com.flydb.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;

/**
 * dry-run 中一份将要执行的迁移脚本及其解析结果；同时携带来源
 * {@code ResolvedMigration} 的标识字段，供 Plan Artifact 计算确定性摘要。
 *
 * <p>{@code version} 为可重复迁移时为 null；{@code checksum} 未知时为 null。
 */
public final class DryRunMigration {
    private final String script;
    private final MigrationType type;
    private final MigrationVersion version;
    private final String description;
    private final Integer checksum;
    private final List<DryRunStatement> statements;

    /**
     * 兼容 0.2.x 的构造器；旧调用方没有迁移元数据时，这些字段保持 null。
     *
     * @deprecated 新代码应传入完整迁移元数据，以便生成可审计的 Plan Artifact。
     */
    @Deprecated
    public DryRunMigration(String script, MigrationType type,
                           List<DryRunStatement> statements) {
        this(script, type, null, null, null, statements);
    }

    public DryRunMigration(String script, MigrationType type, MigrationVersion version,
                           String description, Integer checksum,
                           List<DryRunStatement> statements) {
        if (script == null) throw new NullPointerException("script");
        if (type == null) throw new NullPointerException("type");
        if (statements == null) throw new NullPointerException("statements");
        this.script = script;
        this.type = type;
        this.version = version;
        this.description = description;
        this.checksum = checksum;
        this.statements = Collections.unmodifiableList(
                new ArrayList<DryRunStatement>(statements));
    }

    public String script() { return script; }
    public MigrationType type() { return type; }
    public MigrationVersion version() { return version; }
    public String description() { return description; }
    public Integer checksum() { return checksum; }
    public List<DryRunStatement> statements() { return statements; }
}
