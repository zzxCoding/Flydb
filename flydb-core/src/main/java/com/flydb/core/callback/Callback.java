package com.flydb.core.callback;

import java.sql.Connection;

import com.flydb.core.api.FlydbConfiguration;

/** 命令生命周期回调 SPI（设计 05 §8）。 */
public interface Callback {

    boolean supports(Event event, Context context);

    void handle(Event event, Context context);

    interface Context {
        Connection connection();
        FlydbConfiguration configuration();
    }
}
