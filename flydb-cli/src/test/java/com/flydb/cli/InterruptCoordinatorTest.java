package com.flydb.cli;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InterruptCoordinator")
class InterruptCoordinatorTest {

    @Test
    @DisplayName("SIGINT 先关闭活动资源再使用退出码 5")
    void closesActiveResourceBeforeExitingWithFive() {
        AtomicInteger closed = new AtomicInteger();
        AtomicInteger exitCode = new AtomicInteger(-1);
        InterruptCoordinator coordinator = new InterruptCoordinator(exitCode::set);
        coordinator.register(closed::incrementAndGet);

        coordinator.interrupt();

        assertThat(closed).hasValue(1);
        assertThat(exitCode).hasValue(5);
    }

    @Test
    @DisplayName("正常关闭后 shutdown hook 不会重复关闭资源")
    void clearedResourceIsNotClosedTwice() {
        AtomicInteger closed = new AtomicInteger();
        InterruptCoordinator coordinator = new InterruptCoordinator(code -> { });
        AutoCloseable resource = closed::incrementAndGet;
        coordinator.register(resource);

        coordinator.clear(resource);
        coordinator.closeActive();

        assertThat(closed).hasValue(0);
    }
}
