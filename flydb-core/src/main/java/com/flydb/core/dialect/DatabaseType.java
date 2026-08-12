package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;

/**
 * 数据库类型 SPI（设计 03 §1）：两阶段探测 + 显式覆盖。
 *
 * <p>实现经 {@link java.util.ServiceLoader} 发现（{@code META-INF/services/com.flydb.core.dialect.DatabaseType}）。
 * 内置 8 个 MVP 方言实现，第三方也可通过 SPI 注册。
 *
 * <p>探测流程：
 * <ol>
 *   <li>若配置了 {@code databaseType}，按 {@link #name()} 直接命中，跳过全部探测。</li>
 *   <li>阶段一：以 {@link #handlesUrl(String)} 过滤候选集（URL 前缀判定，权威判定）。</li>
 *   <li>阶段二：对候选按 {@link #priority()} 降序逐个调 {@link #handlesConnection(Connection)} 确认。</li>
 *   <li>零候选 → {@code FLYDB-1002}；多候选歧义 → 同样报错而非猜测。</li>
 * </ol>
 */
public interface DatabaseType {

    /** 稳定标识，如 "opengauss"（配置 {@code databaseType} 时使用）。 */
    String name();

    /** 越大越优先；解决 URL 前缀重叠歧义（TiDB > MySQL）。 */
    int priority();

    /** 阶段一：连接前，URL 前缀判定。 */
    boolean handlesUrl(String jdbcUrl);

    /** 阶段二：连接后，产品名等二次确认。 */
    boolean handlesConnection(Connection connection) throws SQLException;

    /** 创建方言实例。 */
    Database createDatabase(Connection connection, FlydbConfiguration cfg) throws SQLException;
}