package com.flydb.core.migration;

/**
 * 本地解析出的迁移（不可变值对象，设计 02 §4）。
 *
 * <p>由 {@code MigrationResolver} 在扫描脚本/Java 迁移后产出。可重复迁移（R__）的 {@link #version()}
 * 为 {@code null}；Java 迁移的 {@link #checksum()} 允许为 {@code null}（不参与校验）。
 *
 * <p>注：设计 02 §4 草图还含 {@code MigrationExecutor executor()}，该方法与执行抽象（设计 04 §1.4）
 * 强耦合，留待阶段 3 引入 {@code MigrationExecutor} 时补齐——阶段 1 的状态推导逻辑不依赖它。
 */
public interface ResolvedMigration {

    /** 版本号；可重复迁移返回 {@code null}。 */
    MigrationVersion version();

    String description();

    /** 相对路径（SQL）或类全限定名（JDBC）。 */
    String script();

    /** CRC32 校验和；Java 迁移允许 {@code null}。 */
    Integer checksum();

    MigrationType type();

    /** 默认不可变实现工厂。 */
    static ResolvedMigration of(MigrationVersion version, String description, String script,
                                Integer checksum, MigrationType type) {
        return new DefaultResolvedMigration(version, description, script, checksum, type);
    }

    /** 不可变实现（包私有，仅经 {@link #of} 暴露）。 */
    final class DefaultResolvedMigration implements ResolvedMigration {
        private final MigrationVersion version;
        private final String description;
        private final String script;
        private final Integer checksum;
        private final MigrationType type;

        DefaultResolvedMigration(MigrationVersion version, String description, String script,
                                 Integer checksum, MigrationType type) {
            this.version = version;
            this.description = description;
            this.script = script;
            this.checksum = checksum;
            this.type = type;
        }

        @Override public MigrationVersion version() { return version; }
        @Override public String description() { return description; }
        @Override public String script() { return script; }
        @Override public Integer checksum() { return checksum; }
        @Override public MigrationType type() { return type; }
    }
}
