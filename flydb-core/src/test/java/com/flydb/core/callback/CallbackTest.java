package com.flydb.core.callback;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.api.FlydbConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Callback")
class CallbackTest {

    @Test
    @DisplayName("显式回调按注册顺序触发且配置保持不可变")
    void explicitCallbacksFireInRegistrationOrder() {
        List<String> fired = new ArrayList<String>();
        Callback first = recording("first", fired);
        Callback second = recording("second", fired);
        FlydbConfiguration cfg = FlydbConfiguration.builder()
                .dataSource(new NullDataSource())
                .callbacks(first, second)
                .build();

        CallbackDispatcher dispatcher = new CallbackDispatcher(cfg.callbacks());
        dispatcher.fire(Event.BEFORE_MIGRATE, new Callback.Context() {
            @Override public java.sql.Connection connection() { return null; }
            @Override public FlydbConfiguration configuration() { return cfg; }
        });

        assertThat(fired).containsExactly("first:BEFORE_MIGRATE", "second:BEFORE_MIGRATE");
        assertThat(cfg.callbacks()).containsExactly(first, second);
    }

    private static Callback recording(final String name, final List<String> fired) {
        return new Callback() {
            @Override public boolean supports(Event event, Context context) { return true; }
            @Override public void handle(Event event, Context context) {
                fired.add(name + ":" + event.name());
            }
        };
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
