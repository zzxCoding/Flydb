package com.flydb.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * ErrorCode 稳定目录与消息格式契约测试（设计 02 §9、06 §5）。
 *
 * <p>错误码是产品对外契约的一部分（CI 按码分支、用户按码检索修复建议），任何码值/文案变更
 * 都需显式回到设计评审——故此处固化每一段的实际取值。
 */
class ErrorCodeTest {

    @Test
    void code_string_matches_design_catalog() {
        // 1xxx 连接与探测
        assertThat(ErrorCode.CONNECT_FAILED.code()).isEqualTo("FLYDB-1001");
        assertThat(ErrorCode.UNRECOGNIZED_DATABASE_TYPE.code()).isEqualTo("FLYDB-1002");
        assertThat(ErrorCode.DRIVER_NOT_FOUND.code()).isEqualTo("FLYDB-1003");
        // 2xxx 迁移与校验
        assertThat(ErrorCode.INVALID_VERSION.code()).isEqualTo("FLYDB-2001");
        assertThat(ErrorCode.DUPLICATE_VERSION.code()).isEqualTo("FLYDB-2002");
        assertThat(ErrorCode.CHECKSUM_MISMATCH.code()).isEqualTo("FLYDB-2003");
        assertThat(ErrorCode.FAILED_MIGRATION_NEEDS_REPAIR.code()).isEqualTo("FLYDB-2004");
        assertThat(ErrorCode.LEGACY_R_PREFIX_NAMING.code()).isEqualTo("FLYDB-2005");
        assertThat(ErrorCode.OUT_OF_ORDER_MIGRATION.code()).isEqualTo("FLYDB-2006");
        assertThat(ErrorCode.BASELINE_PRECONDITION_UNMET.code()).isEqualTo("FLYDB-2007");
        assertThat(ErrorCode.MISSING_UNDO_SCRIPT.code()).isEqualTo("FLYDB-2008");
        assertThat(ErrorCode.UNDEFINED_PLACEHOLDER.code()).isEqualTo("FLYDB-2009");
        // 3xxx 并发锁
        assertThat(ErrorCode.LOCK_ACQUISITION_TIMEOUT.code()).isEqualTo("FLYDB-3001");
        // 4xxx 配置
        assertThat(ErrorCode.UNKNOWN_CONFIG_KEY.code()).isEqualTo("FLYDB-4001");
        assertThat(ErrorCode.MISSING_REQUIRED_CONFIG.code()).isEqualTo("FLYDB-4002");
        assertThat(ErrorCode.CLEAN_DISABLED.code()).isEqualTo("FLYDB-4003");
    }

    @Test
    void each_code_carries_bilingual_summary_and_cause_and_action() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.code()).as("code 值").startsWith("FLYDB-");
            assertThat(code.zhSummary()).as("中文简述").isNotEmpty();
            assertThat(code.enSummary()).as("英文简述").isNotEmpty();
            assertThat(code.zhCause()).as("可能原因").isNotEmpty();
            assertThat(code.zhAction()).as("建议操作").isNotEmpty();
        }
    }

    @Test
    void invalid_version_carries_design_summaries() {
        ErrorCode code = ErrorCode.INVALID_VERSION;
        assertThat(code.zhSummary()).contains("版本号");
        assertThat(code.enSummary()).containsIgnoringCase("version");
    }
}
