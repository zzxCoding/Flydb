package com.flydb.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * migrate/undo 预演的不可变结果；产生结果的过程不建表、不加锁、不记账。
 *
 * <p>{@code direction} 是计划方向的稳定 token（{@code migrate} / {@code undo}），
 * 参与 Plan Artifact 的确定性摘要，见 {@link PlanArtifact}。
 */
public final class DryRunResult {
    private final String direction;
    private final List<DryRunMigration> migrations;

    /**
     * 兼容 0.2.x 的 migrate dry-run 结果构造器。
     *
     * @deprecated 新代码应显式传入 {@code migrate} 或 {@code undo} 方向。
     */
    @Deprecated
    public DryRunResult(List<DryRunMigration> migrations) {
        this("migrate", migrations);
    }

    public DryRunResult(String direction, List<DryRunMigration> migrations) {
        if (direction == null) throw new NullPointerException("direction");
        if (!"migrate".equals(direction) && !"undo".equals(direction)) {
            throw new IllegalArgumentException("direction 必须是 migrate 或 undo: " + direction);
        }
        if (migrations == null) throw new NullPointerException("migrations");
        this.direction = direction;
        this.migrations = Collections.unmodifiableList(
                new ArrayList<DryRunMigration>(migrations));
    }

    public String direction() { return direction; }
    public List<DryRunMigration> migrations() { return migrations; }
}
