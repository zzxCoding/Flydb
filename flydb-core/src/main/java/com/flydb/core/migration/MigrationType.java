package com.flydb.core.migration;

/**
 * 迁移类型（设计 02 §4、05 §5 历史表 type 列）。
 *
 * <ul>
 *   <li>{@link #SQL} —— 版本化/可重复/撤销 SQL 脚本迁移。</li>
 *   <li>{@link #JDBC} —— Java 迁移（实现 {@code JavaMigration}）。</li>
 *   <li>{@link #BASELINE} —— baseline 合成记录。</li>
 *   <li>{@link #UNDO_SQL} —— 撤销迁移（U 前缀脚本）产生的记录。</li>
 * </ul>
 */
public enum MigrationType {
    SQL,
    JDBC,
    BASELINE,
    UNDO_SQL
}
