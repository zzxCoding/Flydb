package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 方言探测阶段使用的最小 JDBC 查询工具。 */
final class DatabaseProbe {

    private DatabaseProbe() {
    }

    static String queryString(Connection connection, String sql) throws SQLException {
        return queryString(connection, sql, 1);
    }

    static String queryString(Connection connection, String sql, int column) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            ResultSet resultSet = statement.executeQuery(sql);
            try {
                return resultSet.next() ? resultSet.getString(column) : null;
            } finally {
                resultSet.close();
            }
        } finally {
            statement.close();
        }
    }

    static boolean containsIgnoreCase(String value, String token) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT)
                .contains(token.toLowerCase(java.util.Locale.ROOT));
    }
}
