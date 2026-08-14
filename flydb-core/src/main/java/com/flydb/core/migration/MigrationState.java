package com.flydb.core.migration;

/**
 * 迁移状态（设计 02 §6）。由 {@code MigrationInfo.derive(...)} 纯函数据「本地解析 + 历史记录」推导。
 *
 * <ul>
 *   <li>{@link #PENDING} —— 本地有、库里无、版本高于已应用最高版本。</li>
 *   <li>{@link #OUT_OF_ORDER} —— 本地有、库里无、版本低于已应用最高版本（outOfOrder=false 时报错）。</li>
 *   <li>{@link #SUCCESS} —— 已成功应用且 checksum 一致。</li>
 *   <li>{@link #FAILED} —— 历史表存在 success=false 记录，阻塞 migrate、需 repair。</li>
 *   <li>{@link #MISSING} —— 库里有、本地脚本已不存在（非 BASELINE/UNDO）。</li>
 *   <li>{@link #OUTDATED} —— 可重复迁移：本地 checksum 已变化，等待重跑。</li>
 *   <li>{@link #FUTURE} —— 库里记录版本高于本地所有脚本版本（代码回滚、库没回）。</li>
 *   <li>{@link #BASELINE} —— baseline 合成记录。</li>
 *   <li>{@link #UNDONE} —— 该版本最新记录为 UNDO，当前视为未应用。</li>
 * </ul>
 */
public enum MigrationState {
    PENDING,
    OUT_OF_ORDER,
    SUCCESS,
    FAILED,
    MISSING,
    OUTDATED,
    FUTURE,
    BASELINE,
    UNDONE
}
