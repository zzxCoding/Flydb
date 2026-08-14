package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 当前 schema 的 MVP 清理策略：视图、表、序列。 */
public interface CleanStrategy {
    void clean(Connection connection, String schema, List<String> excludedTables) throws SQLException;
}
