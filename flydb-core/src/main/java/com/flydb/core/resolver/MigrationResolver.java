package com.flydb.core.resolver;

import java.util.Collection;

import com.flydb.core.migration.ResolvedMigration;

/**
 * 迁移解析器 SPI（设计 02 §5）。
 *
 * <p>实现经 {@link java.util.ServiceLoader} 发现（{@code META-INF/services/com.flydb.core.resolver.MigrationResolver}），
 * 也可由用户显式注册。内置 {@link SqlMigrationResolver} 扫描 classpath/filesystem 下的 SQL 脚本。
 */
public interface MigrationResolver {

    /**
     * 从给定上下文解析所有迁移。
     *
     * @param context 解析上下文（含扫描路径、编码、命名规范等）
     * @return 已解析的迁移集合（未排序，调用方负责汇总去重后排序）
     */
    Collection<ResolvedMigration> resolveMigrations(ResolverContext context);
}