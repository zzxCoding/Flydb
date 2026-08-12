package com.flydb.core.exception;

/**
 * 单条校验问题（不可变值对象）。
 *
 * <p>{@link FlydbValidationException} 聚合多条，使 {@code validate} 命令能一次性报告全部问题，
 * 而非遇到第一个就抛出（设计 02 §9）。
 */
public final class ValidationProblem {

    private final ErrorCode errorCode;
    private final String detail;

    public ValidationProblem(ErrorCode errorCode, String detail) {
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }
}
