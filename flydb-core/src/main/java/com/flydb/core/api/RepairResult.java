package com.flydb.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** repair 记账修复结果。 */
public final class RepairResult {
    private final List<String> removedFailedRecords;
    private final List<String> alignedChecksums;

    public RepairResult(List<String> removedFailedRecords, List<String> alignedChecksums) {
        this.removedFailedRecords = immutableCopy(removedFailedRecords);
        this.alignedChecksums = immutableCopy(alignedChecksums);
    }

    public List<String> removedFailedRecords() { return removedFailedRecords; }
    public List<String> alignedChecksums() { return alignedChecksums; }

    private static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
