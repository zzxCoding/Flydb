package com.flydb.core.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.ValidationProblem;
import com.flydb.core.migration.MigrationInfo;
import com.flydb.core.migration.MigrationState;
import com.flydb.core.migration.MigrationType;

/** validate 与 validateOnMigrate 共用的聚合校验规则。 */
final class MigrationValidator {

    private MigrationValidator() {
    }

    static List<ValidationProblem> validate(List<MigrationInfo> infos) {
        List<ValidationProblem> problems = new ArrayList<ValidationProblem>();
        for (MigrationInfo info : infos) {
            addChecksumProblem(info, problems);
            addStateProblem(info, problems);
        }
        return Collections.unmodifiableList(problems);
    }

    private static void addChecksumProblem(MigrationInfo info,
                                           List<ValidationProblem> problems) {
        if (info.resolved() == null || info.applied() == null
                || info.resolved().version() == null
                || info.resolved().type() == MigrationType.UNDO_SQL
                || info.applied().type() == MigrationType.UNDO_SQL) {
            return;
        }
        Integer local = info.resolved().checksum();
        Integer recorded = info.applied().checksum();
        if (local != null && recorded != null && !local.equals(recorded)) {
            problems.add(new ValidationProblem(ErrorCode.CHECKSUM_MISMATCH,
                    info.resolved().script() + " 本地 checksum=" + local
                            + "，历史 checksum=" + recorded));
        }
    }

    private static void addStateProblem(MigrationInfo info,
                                        List<ValidationProblem> problems) {
        if (info.state() == MigrationState.FAILED) {
            problems.add(new ValidationProblem(ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR,
                    script(info) + " 存在 success=false 失败记录"));
        } else if (info.state() == MigrationState.MISSING) {
            problems.add(new ValidationProblem(ErrorCode.CHECKSUM_MISMATCH,
                    script(info) + " 已应用但本地文件缺失（MISSING）"));
        } else if (info.state() == MigrationState.FUTURE) {
            problems.add(new ValidationProblem(ErrorCode.CHECKSUM_MISMATCH,
                    script(info) + " 高于本地最高版本（FUTURE）"));
        }
    }

    private static String script(MigrationInfo info) {
        if (info.resolved() != null) {
            return info.resolved().script();
        }
        return info.applied() == null ? "<unknown>" : info.applied().script();
    }
}
