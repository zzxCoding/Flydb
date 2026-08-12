package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/**
 * 数据库类型注册中心（设计 03 §1）。
 *
 * <p>通过 {@link ServiceLoader} 加载所有 {@link DatabaseType} 实现（内置 + 外部 jar），
 * 提供两阶段探测入口。
 */
public final class DatabaseTypeRegistry {

    private final List<DatabaseType> types;

    /** 使用 ServiceLoader 加载所有实现。 */
    public DatabaseTypeRegistry() {
        List<DatabaseType> loaded = new ArrayList<DatabaseType>();
        for (DatabaseType type : ServiceLoader.load(DatabaseType.class)) {
            loaded.add(type);
        }
        this.types = loaded;
    }

    /** 测试用：直接注入预定义的类型列表。 */
    DatabaseTypeRegistry(DatabaseType[] types) {
        this.types = new ArrayList<DatabaseType>(types.length);
        for (DatabaseType type : types) {
            this.types.add(type);
        }
    }

    /**
     * 两阶段数据库类型探测。
     *
     * @param jdbcUrl      JDBC URL（阶段一判定）
     * @param connection   已建立的连接（阶段二判定，可 null）
     * @param explicitType 显式指定的方言名（null 表示自动探测）
     * @return 探测匹配的 {@link DatabaseType}
     * @throws FlydbException(FLYDB-1002) 零候选或歧义
     * @throws FlydbException(FLYDB-1002) 显式指定类型不存在
     */
    public DatabaseType detect(String jdbcUrl, Connection connection, String explicitType) {
        // 1) 显式指定
        if (explicitType != null && !explicitType.isEmpty()) {
            for (DatabaseType type : types) {
                if (type.name().equals(explicitType)) {
                    return type;
                }
            }
            throw new FlydbException(ErrorCode.UNRECOGNIZED_DATABASE_TYPE,
                    "显式指定的数据库类型不存在: " + explicitType
                            + "（支持列表: " + listSupported() + "）");
        }

        // 2) 阶段一：URL 前缀过滤
        List<DatabaseType> candidates = new ArrayList<DatabaseType>();
        for (DatabaseType type : types) {
            if (type.handlesUrl(jdbcUrl)) {
                candidates.add(type);
            }
        }
        if (candidates.isEmpty()) {
            throw new FlydbException(ErrorCode.UNRECOGNIZED_DATABASE_TYPE,
                    "无法识别 JDBC URL 前缀: " + jdbcUrl
                            + "（支持列表: " + listSupported() + "）");
        }

        // 3) 仅一个候选且无连接 → 直接返回（URL 前缀即权威，无需阶段二确认）
        if (candidates.size() == 1 && connection == null) {
            return candidates.get(0);
        }

        // 4) 单候选→直接返回（URL 前缀即权威，设计 03 §1.1 禁止阶段二推翻阶段一）
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // 5) 多候选→阶段二消歧（按 priority 降序）
        if (connection != null) {
            List<DatabaseType> confirmed = new ArrayList<DatabaseType>();
            candidates.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
            for (DatabaseType type : candidates) {
                try {
                    if (type.handlesConnection(connection)) {
                        confirmed.add(type);
                    }
                } catch (SQLException e) {
                    // 单个探测失败不中止整体流程
                }
            }
            if (confirmed.isEmpty()) {
                throw new FlydbException(ErrorCode.UNRECOGNIZED_DATABASE_TYPE,
                        "JDBC URL " + jdbcUrl + " 的前缀匹配了 " + candidates.size()
                                + " 个候选类型，但连接后均无法确认（"
                                + "考虑使用 --database-type 显式指定）");
            }
            if (confirmed.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (DatabaseType t : confirmed) {
                    sb.append(t.name()).append(", ");
                }
                throw new FlydbException(ErrorCode.UNRECOGNIZED_DATABASE_TYPE,
                        "JDBC URL " + jdbcUrl + " 匹配了多个候选类型: " + sb.toString()
                                + "请使用 --database-type 显式指定");
            }
            return confirmed.get(0);
        }

        // 6) 多候选且无连接 → 歧义报错
        StringBuilder sb = new StringBuilder();
        for (DatabaseType t : candidates) {
            sb.append(t.name()).append(", ");
        }
        throw new FlydbException(ErrorCode.UNRECOGNIZED_DATABASE_TYPE,
                "JDBC URL " + jdbcUrl + " 匹配了多个候选类型，需连接后二次确认: " + sb.toString());
    }

    private String listSupported() {
        StringBuilder sb = new StringBuilder();
        for (DatabaseType type : types) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(type.name());
        }
        return sb.length() > 0 ? sb.toString() : "（无已注册的方言）";
    }
}