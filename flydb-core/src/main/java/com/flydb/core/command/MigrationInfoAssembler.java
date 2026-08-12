package com.flydb.core.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

/** 将本地解析结果与历史记录组装成 info 真值表视图。 */
final class MigrationInfoAssembler {

    private MigrationInfoAssembler() {
    }

    static List<MigrationInfo> assemble(List<ResolvedMigration> resolved,
                                        List<AppliedMigration> applied) {
        List<ResolvedMigration> visible = withoutUndoScripts(resolved);
        Map<MigrationVersion, AppliedMigration> byVersion = latestByVersion(applied);
        Map<String, AppliedMigration> repeatableByScript = latestRepeatables(applied);
        MigrationVersion latestApplied = latestSuccessfulVersion(byVersion);
        MigrationVersion latestResolved = latestResolvedVersion(visible);
        Set<AppliedMigration> matched = new HashSet<AppliedMigration>();
        List<MigrationInfo> result = new ArrayList<MigrationInfo>();

        for (ResolvedMigration migration : visible) {
            AppliedMigration record = migration.version() == null
                    ? repeatableByScript.get(migration.script())
                    : byVersion.get(migration.version());
            if (record != null) {
                matched.add(record);
            }
            result.add(MigrationInfo.derive(migration, record, latestApplied, latestResolved));
        }
        List<AppliedMigration> latestHistory = new ArrayList<AppliedMigration>(byVersion.values());
        latestHistory.addAll(repeatableByScript.values());
        addUnmatchedHistory(result, latestHistory, matched, latestApplied, latestResolved);
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private static List<ResolvedMigration> withoutUndoScripts(List<ResolvedMigration> resolved) {
        List<ResolvedMigration> result = new ArrayList<ResolvedMigration>();
        for (ResolvedMigration migration : resolved) {
            if (migration.type() != MigrationType.UNDO_SQL) {
                result.add(migration);
            }
        }
        return result;
    }

    private static Map<MigrationVersion, AppliedMigration> latestByVersion(
            List<AppliedMigration> applied) {
        Map<MigrationVersion, AppliedMigration> result = new HashMap<MigrationVersion, AppliedMigration>();
        for (AppliedMigration record : applied) {
            if (record.version() != null) {
                putLatest(result, record.version(), record);
            }
        }
        return result;
    }

    private static Map<String, AppliedMigration> latestRepeatables(List<AppliedMigration> applied) {
        Map<String, AppliedMigration> result = new HashMap<String, AppliedMigration>();
        for (AppliedMigration record : applied) {
            if (record.version() == null) {
                putLatest(result, record.script(), record);
            }
        }
        return result;
    }

    private static <K> void putLatest(Map<K, AppliedMigration> target, K key,
                                      AppliedMigration candidate) {
        AppliedMigration current = target.get(key);
        if (current == null || candidate.installedRank() > current.installedRank()) {
            target.put(key, candidate);
        }
    }

    private static MigrationVersion latestSuccessfulVersion(
            Map<MigrationVersion, AppliedMigration> byVersion) {
        MigrationVersion result = null;
        for (Map.Entry<MigrationVersion, AppliedMigration> entry : byVersion.entrySet()) {
            AppliedMigration record = entry.getValue();
            if (!record.success() || record.type() == MigrationType.UNDO_SQL) {
                continue;
            }
            if (result == null || entry.getKey().compareTo(result) > 0) {
                result = entry.getKey();
            }
        }
        return result;
    }

    private static MigrationVersion latestResolvedVersion(List<ResolvedMigration> resolved) {
        MigrationVersion result = null;
        for (ResolvedMigration migration : resolved) {
            if (migration.version() != null
                    && (result == null || migration.version().compareTo(result) > 0)) {
                result = migration.version();
            }
        }
        return result;
    }

    private static void addUnmatchedHistory(List<MigrationInfo> target,
                                            List<AppliedMigration> applied,
                                            Set<AppliedMigration> matched,
                                            MigrationVersion latestApplied,
                                            MigrationVersion latestResolved) {
        Set<String> added = new HashSet<String>();
        for (AppliedMigration record : applied) {
            String identity = record.version() == null
                    ? "script:" + record.script() : "version:" + record.version();
            if (!matched.contains(record) && added.add(identity)) {
                target.add(MigrationInfo.derive(null, record, latestApplied, latestResolved));
            }
        }
    }
}
