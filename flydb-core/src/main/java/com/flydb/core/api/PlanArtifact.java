package com.flydb.core.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Plan Artifact v1：一份 dry-run 计划的确定性摘要（设计 11）。
 *
 * <p>同一份脚本集合、同一顺序、同一 checksum 与切分结果必然得到同一 {@code id}；
 * 摘要不含时间戳、绝对路径或数据库状态，供人、CI 与 Agent 指称“同一份计划”。
 * 摘要算法与规范文本见 {@link #canonicalText}；破坏性变更会换用新
 * {@code algorithm} token，不改动 v1 语义。
 */
public final class PlanArtifact {
    /** 摘要算法 token，同时是规范文本首行。 */
    public static final String ALGORITHM = "flydb-plan-v1";

    private final String direction;
    private final String id;
    private final String targetVersion;
    private final int migrationCount;
    private final int statementCount;

    private PlanArtifact(String direction, String id, String targetVersion,
                         int migrationCount, int statementCount) {
        this.direction = direction;
        this.id = id;
        this.targetVersion = targetVersion;
        this.migrationCount = migrationCount;
        this.statementCount = statementCount;
    }

    public static PlanArtifact of(DryRunResult result) {
        if (result == null) throw new NullPointerException("result");
        String targetVersion = null;
        int statementCount = 0;
        for (DryRunMigration migration : result.migrations()) {
            if (migration.version() != null) targetVersion = migration.version().toString();
            statementCount += migration.statements().size();
        }
        return new PlanArtifact(result.direction(), sha256Hex(canonicalText(result)),
                targetVersion, result.migrations().size(), statementCount);
    }

    /** 摘要方向 token：{@code migrate} / {@code undo}。 */
    public String direction() { return direction; }
    /** 规范文本的 SHA-256，64 位小写十六进制。 */
    public String id() { return id; }
    /** 计划中最后一个版本化迁移的版本；计划为空或只含可重复迁移时为 null。 */
    public String targetVersion() { return targetVersion; }
    public int migrationCount() { return migrationCount; }
    public int statementCount() { return statementCount; }

    /**
     * 规范文本（契约）：首行算法 token，次行方向 token，随后每个迁移一行
     * {@code version\ttype\tscript\tchecksum\tstatementCount}（null 为空串），
     * 每行以 {@code \n} 结束。字段顺序与取值规则不得变更，否则 {@code id} 语义破坏。
     */
    static String canonicalText(DryRunResult result) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(ALGORITHM).append('\n').append(result.direction()).append('\n');
        for (DryRunMigration migration : result.migrations()) {
            canonical.append(migration.version() == null ? "" : migration.version().toString())
                    .append('\t').append(migration.type().name())
                    .append('\t').append(migration.script())
                    .append('\t').append(migration.checksum() == null ? ""
                            : migration.checksum().toString())
                    .append('\t').append(migration.statements().size())
                    .append('\n');
        }
        return canonical.toString();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16))
                        .append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
