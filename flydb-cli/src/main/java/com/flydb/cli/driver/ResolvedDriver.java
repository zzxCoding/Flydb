package com.flydb.cli.driver;

import java.net.URL;

/** DriverResolver 的不可变解析结果。 */
public final class ResolvedDriver {
    private final String driverClass;
    private final URL[] urls;
    private final String source;

    ResolvedDriver(String driverClass, URL[] urls, String source) {
        this.driverClass = driverClass;
        this.urls = urls.clone();
        this.source = source;
    }

    public String driverClass() { return driverClass; }
    public URL[] urls() { return urls.clone(); }
    public String source() { return source; }
}
