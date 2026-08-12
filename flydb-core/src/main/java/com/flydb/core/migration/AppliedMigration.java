package com.flydb.core.migration;

import java.sql.Timestamp;

/**
 * 历史表（{@code flydb_schema_history}）中已记录的迁移（不可变值对象，设计 02 §4、00 §5）。
 *
 * <p>由 {@code SchemaHistory} 仓储从历史表读出。{@link #installedRank()} 是单调递增的记账序号
 * （<b>不是</b>版本号，修复旧原型缺陷 #3）；{@link #version()} 对可重复迁移为 {@code null}。
 */
public interface AppliedMigration {

    /** 应用顺序序号，单调递增（当前最大值 + 1）。 */
    int installedRank();

    /** 版本号原文；可重复迁移为 {@code null}。 */
    MigrationVersion version();

    String description();

    MigrationType type();

    String script();

    /** CRC32 校验和；可空。 */
    Integer checksum();

    /** 执行时的数据库用户。 */
    String installedBy();

    /** 记录写入时间（{@link Timestamp} 贴合 JDBC）。 */
    Timestamp installedOn();

    /** 执行耗时（毫秒）。 */
    int executionTimeMillis();

    /** 是否成功；FAILED 记录为 {@code false}，阻塞 migrate、需 repair。 */
    boolean success();

    /** 默认不可变实现工厂。 */
    static AppliedMigration of(int installedRank, MigrationVersion version, String description,
                               MigrationType type, String script, Integer checksum,
                               String installedBy, Timestamp installedOn,
                               int executionTimeMillis, boolean success) {
        return new DefaultAppliedMigration(installedRank, version, description, type, script,
                checksum, installedBy, installedOn, executionTimeMillis, success);
    }

    /** 不可变实现（包私有，仅经 {@link #of} 暴露）。 */
    final class DefaultAppliedMigration implements AppliedMigration {
        private final int installedRank;
        private final MigrationVersion version;
        private final String description;
        private final MigrationType type;
        private final String script;
        private final Integer checksum;
        private final String installedBy;
        private final Timestamp installedOn;
        private final int executionTimeMillis;
        private final boolean success;

        DefaultAppliedMigration(int installedRank, MigrationVersion version, String description,
                                MigrationType type, String script, Integer checksum,
                                String installedBy, Timestamp installedOn,
                                int executionTimeMillis, boolean success) {
            this.installedRank = installedRank;
            this.version = version;
            this.description = description;
            this.type = type;
            this.script = script;
            this.checksum = checksum;
            this.installedBy = installedBy;
            // Timestamp 可变——防御性拷贝
            this.installedOn = installedOn == null ? null : new Timestamp(installedOn.getTime());
            this.executionTimeMillis = executionTimeMillis;
            this.success = success;
        }

        @Override public int installedRank() { return installedRank; }
        @Override public MigrationVersion version() { return version; }
        @Override public String description() { return description; }
        @Override public MigrationType type() { return type; }
        @Override public String script() { return script; }
        @Override public Integer checksum() { return checksum; }
        @Override public String installedBy() { return installedBy; }
        @Override public Timestamp installedOn() {
            return installedOn == null ? null : new Timestamp(installedOn.getTime());
        }
        @Override public int executionTimeMillis() { return executionTimeMillis; }
        @Override public boolean success() { return success; }
    }
}
