package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.flydb.core.log.Log;
import com.flydb.core.log.LogFactory;

/**
 * Oracle 家族 schema 清理（设计 03 §3.3）。
 *
 * <p>与 {@link MetadataCleanStrategy} 的两点方言差异：
 * <ol>
 *   <li>JDBC {@code getTables} 不返回 Oracle 序列，且 Oracle 没有
 *       {@code information_schema}——序列清单改查 {@code user_sequences}，
 *       并跳过随表生存的 identity 序列（{@code ISEQ$$_}）与回收站对象（{@code BIN$}）。</li>
 *   <li>{@code DROP TABLE} 追加 {@code PURGE} 并收尾 {@code PURGE RECYCLEBIN}，
 *       避免回收站残留的表、LOB 段、索引和 identity 序列占用名称，
 *       导致后续迁移的 {@code CREATE} 撞名（ORA-00955 等）。</li>
 * </ol>
 */
final class OracleCleanStrategy implements CleanStrategy {

    @Override
    public void clean(Connection connection, String schema, List<String> excludedTables)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> views = objects(metadata, schema, new String[]{"VIEW"});
        List<String> tables = objects(metadata, schema, new String[]{"TABLE"});
        // Oracle 非引用标识符按大写存储，配置中的小写记账表名需忽略大小写排除，
        // 它们由 clean 收尾按建表时的标识符形式单独删除
        for (String excluded : excludedTables) {
            for (java.util.Iterator<String> iterator = tables.iterator(); iterator.hasNext(); ) {
                if (iterator.next().equalsIgnoreCase(excluded)) {
                    iterator.remove();
                }
            }
        }
        List<String> sequences = userSequences(connection);
        Log log = LogFactory.getLog(OracleCleanStrategy.class);
        log.info("发现待清理对象：视图 " + views.size() + "，表 " + tables.size()
                + "，序列 " + sequences.size());
        Statement statement = connection.createStatement();
        try {
            dropAll(statement, "VIEW", "视图", views, "", log);
            dropAll(statement, "TABLE", "表", tables, " PURGE", log);
            dropAll(statement, "SEQUENCE", "序列", sequences, "", log);
            purgeRecyclebin(statement, log);
        } finally {
            statement.close();
        }
    }

    private static List<String> objects(DatabaseMetaData metadata, String schema,
                                        String[] types) throws SQLException {
        List<String> result = new ArrayList<String>();
        ResultSet rs = metadata.getTables(null, schema, "%", types);
        try {
            while (rs.next()) result.add(rs.getString("TABLE_NAME"));
        } finally {
            rs.close();
        }
        return result;
    }

    private static List<String> userSequences(Connection connection) throws SQLException {
        List<String> result = new ArrayList<String>();
        PreparedStatement statement = connection.prepareStatement(
                "SELECT sequence_name FROM user_sequences");
        ResultSet rs = statement.executeQuery();
        try {
            while (rs.next()) {
                String name = rs.getString(1);
                // ISEQ$$_ 随所属表生存（DROP TABLE ... PURGE 一并清除），BIN$ 是回收站对象
                if (name.startsWith("ISEQ$$_") || name.startsWith("BIN$")) {
                    continue;
                }
                result.add(name);
            }
        } finally {
            rs.close();
            statement.close();
        }
        return result;
    }

    private static void dropAll(Statement statement, String kind, String label,
                                List<String> objects, String suffix, Log log)
            throws SQLException {
        for (int i = 0; i < objects.size(); i++) {
            String object = objects.get(i);
            log.info("正在删除" + label + " " + (i + 1) + "/" + objects.size()
                    + ": " + object);
            statement.execute("DROP " + kind + " " + quoted(object) + suffix);
        }
    }

    /** 清空当前用户回收站；部分兼容方言（如达梦）未启用回收站时降级为警告。 */
    private static void purgeRecyclebin(Statement statement, Log log) {
        try {
            statement.execute("PURGE RECYCLEBIN");
            log.info("回收站已清空");
        } catch (SQLException e) {
            log.warn("PURGE RECYCLEBIN 失败（当前实例可能未启用回收站），已跳过: "
                    + e.getMessage());
        }
    }

    private static String quoted(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
