package com.flydb.core.exception;

/**
 * 所有 Flydb 业务异常的基类（设计 02 §9、06 §5）。
 *
 * <p>每个异常携带稳定的 {@link ErrorCode}，{@link #getMessage()} 产出固定结构的多行文案：
 * <pre>
 * [FLYDB-3001] 获取迁移锁超时（Lock acquisition timed out）
 * 可能原因: ...
 * 建议操作: ...
 * 详情: ...（仅当 detail 非空时出现）
 * </pre>
 * CLI 直接渲染该消息；退出码由命令层据 {@link #errorCode()} 映射（06 §5）。
 *
 * <p>不可变：构造完成后 {@link ErrorCode} 与 detail 不再可变。
 */
public class FlydbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final String detail;

    public FlydbException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public FlydbException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public FlydbException(ErrorCode errorCode, String detail, Throwable cause) {
        super(formatMessage(errorCode, detail), cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    private static String formatMessage(ErrorCode code, String detail) {
        StringBuilder sb = new StringBuilder(128)
                .append('[').append(code.code()).append("] ")
                .append(code.zhSummary()).append("（").append(code.enSummary()).append("）")
                .append('\n').append("可能原因: ").append(code.zhCause())
                .append('\n').append("建议操作: ").append(code.zhAction());
        if (detail != null && !detail.isEmpty()) {
            sb.append('\n').append("详情: ").append(detail);
        }
        return sb.toString();
    }

    /** 稳定错误码，命令层据此映射退出码与重试策略。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 动态详情（可空），例如冲突文件路径、占位符名 + 行号。 */
    public String detail() {
        return detail;
    }
}
