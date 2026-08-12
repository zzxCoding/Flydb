package com.flydb.core.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 校验类异常：聚合多个 {@link ValidationProblem} 一次性抛出（设计 02 §9）。
 *
 * <p>{@code validate} 命令扫描全部脚本/记录后收集所有问题再报告，避免「修一个重跑一次」的低效循环。
 * {@link #getMessage()} 汇总每条问题的码与详情。
 */
public final class FlydbValidationException extends FlydbException {

    private static final long serialVersionUID = 1L;

    private final List<ValidationProblem> problems;

    public FlydbValidationException(List<ValidationProblem> problems) {
        super(ErrorCode.CHECKSUM_MISMATCH, join(problems), null);
        // 防御性拷贝 + 不可变视图
        this.problems = Collections.unmodifiableList(new ArrayList<ValidationProblem>(problems));
    }

    private static String join(List<ValidationProblem> problems) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < problems.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            ValidationProblem p = problems.get(i);
            sb.append('[').append(p.errorCode().code()).append("] ").append(p.detail());
        }
        return sb.toString();
    }

    /** 全部问题的不可变视图。 */
    public List<ValidationProblem> problems() {
        return problems;
    }
}
