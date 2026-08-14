package com.flydb.core.callback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 按注册顺序同步触发回调。 */
public final class CallbackDispatcher {

    private final List<Callback> callbacks;

    public CallbackDispatcher(List<Callback> callbacks) {
        this.callbacks = Collections.unmodifiableList(new ArrayList<Callback>(callbacks));
    }

    public void fire(Event event, Callback.Context context) {
        for (Callback callback : callbacks) {
            if (callback.supports(event, context)) {
                callback.handle(event, context);
            }
        }
    }
}
