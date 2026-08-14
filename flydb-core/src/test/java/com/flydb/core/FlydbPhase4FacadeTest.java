package com.flydb.core;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.RepairResult;
import com.flydb.core.api.UndoResult;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Flydb 阶段 4 门面")
class FlydbPhase4FacadeTest {

    @Test
    @DisplayName("暴露其余五个命令且 clean 默认在连接前拒绝")
    void exposesPhase4CommandsAndCleanIsDisabledByDefault() {
        Flydb flydb = Flydb.configure().dataSource(new NullDataSource()).load();
        Runnable validate = flydb::validate;
        Runnable baseline = flydb::baseline;
        Supplier<RepairResult> repair = flydb::repair;
        Supplier<UndoResult> undo = flydb::undo;

        assertThat(validate).isNotNull();
        assertThat(baseline).isNotNull();
        assertThat(repair).isNotNull();
        assertThat(undo).isNotNull();
        assertThatThrownBy(flydb::clean)
                .isInstanceOf(FlydbException.class)
                .extracting(error -> ((FlydbException) error).errorCode())
                .isEqualTo(ErrorCode.CLEAN_DISABLED);
    }

    private static final class NullDataSource implements javax.sql.DataSource {
        @Override public java.sql.Connection getConnection() { return null; }
        @Override public java.sql.Connection getConnection(String u, String p) { return null; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
