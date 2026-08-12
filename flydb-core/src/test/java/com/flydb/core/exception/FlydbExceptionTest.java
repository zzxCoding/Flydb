package com.flydb.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * FlydbException / FlydbValidationException 消息格式与聚合契约测试（设计 02 §9、06 §5）。
 *
 * <p>getMessage() 必须产出固定结构的多行文案：[FLYDB-xxxx] 中文简述（English summary）
 * + 可能原因 + 建议操作 [+ 详情]，便于 CLI 渲染与用户检索。
 */
class FlydbExceptionTest {

    @Test
    void message_contains_code_summaries_cause_and_action() {
        FlydbException ex = new FlydbException(ErrorCode.LOCK_ACQUISITION_TIMEOUT);

        String message = ex.getMessage();
        assertThat(message).contains("[FLYDB-3001]");
        assertThat(message).contains("获取迁移锁超时");
        assertThat(message).contains("Lock acquisition timed out");
        assertThat(message).contains("可能原因:");
        assertThat(message).contains("建议操作:");
    }

    @Test
    void detail_is_appended_when_provided() {
        FlydbException ex = new FlydbException(
                ErrorCode.DUPLICATE_VERSION, "V1__a.sql 与 V1__b.sql 均解析为版本 1");

        assertThat(ex.getMessage()).contains("[FLYDB-2002]");
        assertThat(ex.getMessage()).contains("详情:");
        assertThat(ex.getMessage()).contains("V1__a.sql 与 V1__b.sql 均解析为版本 1");
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.DUPLICATE_VERSION);
    }

    @Test
    void detail_section_is_omitted_when_blank() {
        FlydbException ex = new FlydbException(ErrorCode.CONNECT_FAILED);

        assertThat(ex.getMessage()).doesNotContain("详情:");
    }

    @Test
    void wraps_cause() {
        Exception cause = new java.sql.SQLException("Connection refused");
        FlydbException ex = new FlydbException(ErrorCode.CONNECT_FAILED, "jdbc:postgresql://db", cause);

        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void validation_exception_aggregates_all_problems() {
        FlydbValidationException ex = new FlydbValidationException(Arrays.asList(
                new ValidationProblem(ErrorCode.CHECKSUM_MISMATCH, "V1 checksum 不匹配"),
                new ValidationProblem(ErrorCode.MISSING_UNDO_SCRIPT, "缺少 U1__undo.sql")));

        assertThat(ex.problems()).hasSize(2);
        assertThat(ex.problems().get(0).errorCode()).isEqualTo(ErrorCode.CHECKSUM_MISMATCH);
        assertThat(ex.problems().get(1).detail()).isEqualTo("缺少 U1__undo.sql");
        // validate 收集全部问题一次性抛出（设计 02 §9）
        assertThat(ex.getMessage()).contains("FLYDB-2003");
        assertThat(ex.getMessage()).contains("FLYDB-2008");
    }
}
