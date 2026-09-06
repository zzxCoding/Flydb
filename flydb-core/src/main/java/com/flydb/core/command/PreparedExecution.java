package com.flydb.core.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.flydb.core.api.*;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.executor.SqlMigrationExecutor;
import com.flydb.core.executor.SqlStatement;
import com.flydb.core.migration.ResolvedMigration;

/** Parse once, compare under the migration lock, then execute these exact executor instances. */
final class PreparedExecution {
    private final Map<ResolvedMigration, SqlMigrationExecutor> executors =
            new LinkedHashMap<ResolvedMigration, SqlMigrationExecutor>();
    private final PreparedMigrationPlan plan;

    PreparedExecution(CommandRuntime runtime, List<ResolvedMigration> migrations, String direction,
                      PreparedMigrationPlan expected) {
        if (expected != null) runtime.previewTimestamp(expected.timestamp());
        List<DryRunMigration> previews = new ArrayList<DryRunMigration>();
        for (ResolvedMigration migration : migrations) {
            SqlMigrationExecutor executor = MigrationCommandSupport.executor(runtime, migration);
            List<DryRunStatement> statements = new ArrayList<DryRunStatement>();
            for (SqlStatement statement : executor.statements()) {
                statements.add(new DryRunStatement(statement.lineNumber(), statement.sql()));
            }
            executors.put(migration, executor);
            previews.add(new DryRunMigration(migration.script(), migration.type(), migration.version(),
                    migration.description(), migration.checksum(), statements));
        }
        plan = new PreparedMigrationPlan(new DryRunResult(direction, previews), target(runtime),
                runtime.builtIns().get("timestamp"));
        if (expected != null && (!plan.targetBinding().equals(expected.targetBinding())
                || !PlanArtifact.of(plan.preview()).id().equals(PlanArtifact.of(expected.preview()).id()))) {
            throw new FlydbException(ErrorCode.PLAN_CHANGED,
                    "目标、待执行集合或 SQL 已变化，请重新预演并核对执行计划");
        }
    }

    PreparedMigrationPlan plan() { return plan; }
    SqlMigrationExecutor executor(ResolvedMigration migration) { return executors.get(migration); }

    private static String target(CommandRuntime runtime) {
        try {
            // Hash length-prefixed fields to keep URL credentials out of the public binding.
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String[] fields = {runtime.connection().getMetaData().getURL(), runtime.database().name(),
                    runtime.database().currentSchema(), runtime.database().currentUser(),
                    runtime.configuration().table()};
            for (String field : fields) {
                byte[] bytes = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.CONNECT_FAILED, "读取计划目标失败", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
