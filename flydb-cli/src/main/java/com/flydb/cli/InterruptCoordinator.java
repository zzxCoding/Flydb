package com.flydb.cli;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 协调 SIGINT、shutdown hook 与当前命令资源的关闭顺序。 */
final class InterruptCoordinator {

    interface ExitHandler { void exit(int code); }

    private final ExitHandler exitHandler;
    private final AtomicReference<AutoCloseable> active =
            new AtomicReference<AutoCloseable>();
    private final AtomicBoolean installed = new AtomicBoolean();
    @SuppressWarnings("unused")
    private Object signalHandler;

    InterruptCoordinator(ExitHandler exitHandler) {
        this.exitHandler = exitHandler;
    }

    void register(AutoCloseable resource) {
        active.set(resource);
    }

    void clear(AutoCloseable resource) {
        active.compareAndSet(resource, null);
    }

    void closeActive() {
        AutoCloseable resource = active.getAndSet(null);
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // 中断退出码优先。
        }
    }

    void interrupt() {
        closeActive();
        exitHandler.exit(5);
    }

    void install() {
        if (!installed.compareAndSet(false, true)) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeActive,
                "flydb-shutdown"));
        installSignalHandler();
    }

    private void installSignalHandler() {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object interruptSignal = signalClass.getConstructor(String.class).newInstance("INT");
            signalHandler = Proxy.newProxyInstance(InterruptCoordinator.class.getClassLoader(),
                    new Class<?>[]{handlerClass}, (proxy, method, args) -> {
                        if ("handle".equals(method.getName())) interrupt();
                        return null;
                    });
            signalClass.getMethod("handle", signalClass, handlerClass)
                    .invoke(null, interruptSignal, signalHandler);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // 精确退出码依赖常见 HotSpot/OpenJDK Signal API；shutdown hook 仍保证释放资源。
        }
    }
}
