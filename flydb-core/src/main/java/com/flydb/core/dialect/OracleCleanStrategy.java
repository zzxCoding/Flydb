package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.flydb.core.log.Log;
import com.flydb.core.log.LogFactory;

/**
 * Oracle 家族 schema 清理（设计 03 §3.3）。
 *
 * <p>与 {@link MetadataCleanStrategy} 的方言差异：
 * <ol>
 *   <li>JDBC {@code getTables} 不返回 Oracle 序列，且 Oracle 没有
 *       {@code information_schema}——序列清单按待清理 schema 查询
 *       {@code all_sequences}，
 *       并跳过随表生存的 identity 序列（{@code ISEQ$$_}）与回收站对象（{@code BIN$}）。</li>
 *   <li>{@code DROP TABLE} 追加 {@code PURGE} 并收尾 {@code PURGE RECYCLEBIN}，
 *       避免回收站残留的表、LOB 段、索引和 identity 序列占用名称，
 *       导致后续迁移的 {@code CREATE} 撞名（ORA-00955 等）。</li>
 *   <li>OceanBase 删除表返回瞬态 {@code -4007} 时，等待目录中的列数稳定后有限重试。</li>
 * </ol>
 */
final class OracleCleanStrategy implements CleanStrategy {

    private static final int OB_DDL_IN_PROGRESS = -4007;
    private static final int ORA_INTERNAL_ERROR = 600;
    private static final Pattern OB_DDL_IN_PROGRESS_ARGUMENT = Pattern.compile(
            "(?i)arguments\\s*:\\s*\\[?\\s*-4007(?!\\d)");
    private static final int DEFAULT_MAX_DROP_ATTEMPTS = 3;
    private static final int DEFAULT_MAX_COLUMN_POLLS = 30;
    private static final long DEFAULT_COLUMN_POLL_MILLIS = 1000L;

    private final int maxDropAttempts;
    private final int maxColumnPolls;
    private final long columnPollMillis;

    OracleCleanStrategy() {
        this(DEFAULT_MAX_DROP_ATTEMPTS, DEFAULT_MAX_COLUMN_POLLS,
                DEFAULT_COLUMN_POLL_MILLIS);
    }

    OracleCleanStrategy(int maxDropAttempts, int maxColumnPolls,
                        long columnPollMillis) {
        this.maxDropAttempts = maxDropAttempts;
        this.maxColumnPolls = maxColumnPolls;
        this.columnPollMillis = columnPollMillis;
    }

    @Override
    public void clean(Connection connection, String schema, List<String> excludedTables)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<String> views = objects(metadata, schema, new String[]{"VIEW"});
        List<String> tables = objects(metadata, schema, new String[]{"TABLE"});
        for (java.util.Iterator<String> iterator = tables.iterator(); iterator.hasNext(); ) {
            if (isObHiddenDdlTable(iterator.next())) {
                iterator.remove();
            }
        }
        // Oracle 非引用标识符按大写存储，配置中的小写记账表名需忽略大小写排除，
        // 它们由 clean 收尾按建表时的标识符形式单独删除
        for (String excluded : excludedTables) {
            for (java.util.Iterator<String> iterator = tables.iterator(); iterator.hasNext(); ) {
                if (iterator.next().equalsIgnoreCase(excluded)) {
                    iterator.remove();
                }
            }
        }
        List<String> sequences = schemaSequences(connection, schema);
        Log log = LogFactory.getLog(OracleCleanStrategy.class);
        log.info("发现待清理对象：视图 " + views.size() + "，表 " + tables.size()
                + "，序列 " + sequences.size());
        Statement statement = connection.createStatement();
        try {
            dropAll(statement, "VIEW", "视图", views, "", log);
            dropTables(connection, statement, schema, tables, log);
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

    private static List<String> schemaSequences(Connection connection, String schema)
            throws SQLException {
        List<String> result = new ArrayList<String>();
        boolean scoped = schema != null && !schema.isEmpty();
        PreparedStatement statement = connection.prepareStatement(scoped
                ? "SELECT sequence_name FROM all_sequences WHERE sequence_owner = ?"
                : "SELECT sequence_name FROM all_sequences WHERE sequence_owner = "
                        + "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')");
        if (scoped) {
            statement.setString(1, schema);
        }
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

    private void dropTables(Connection connection, Statement statement, String schema,
                            List<String> tables, Log log) throws SQLException {
        for (int i = 0; i < tables.size(); i++) {
            String table = tables.get(i);
            log.info("正在删除表 " + (i + 1) + "/" + tables.size() + ": " + table);
            dropTable(connection, statement, schema, table, log);
        }
    }

    private void dropTable(Connection connection, Statement statement, String schema,
                           String table, Log log) throws SQLException {
        String sql = "DROP TABLE " + quoted(table) + " PURGE";
        for (int attempt = 1; attempt <= maxDropAttempts; attempt++) {
            try {
                statement.execute(sql);
                return;
            } catch (SQLException e) {
                if (!isObDdlInProgress(e) || attempt == maxDropAttempts) {
                    throw e;
                }
                log.warn("表 " + table + " 的 DDL 仍在进行（OceanBase -4007），"
                        + "等待列数稳定后重试 " + (attempt + 1) + "/" + maxDropAttempts);
                if (!waitUntilColumnCountStable(connection, schema, table, log)) {
                    throw e;
                }
            }
        }
    }

    private static boolean isObDdlInProgress(SQLException error) {
        if (error.getErrorCode() == OB_DDL_IN_PROGRESS) {
            return true;
        }
        String message = error.getMessage();
        return error.getErrorCode() == ORA_INTERNAL_ERROR
                && message != null
                && OB_DDL_IN_PROGRESS_ARGUMENT.matcher(message).find();
    }

    private static boolean isObHiddenDdlTable(String table) {
        // OB 在线 DDL 会短暂把 _...hidden... 中间表暴露给 JDBC metadata；
        // 不过滤普通 HIDDEN_* 业务表，只跳过以下划线开头的内部形态。
        return table != null
                && table.startsWith("_")
                && table.toLowerCase(Locale.ROOT).contains("hidden");
    }

    private boolean waitUntilColumnCountStable(Connection connection, String schema,
                                               String table, Log log) {
        if (schema == null || schema.isEmpty()) {
            log.warn("无法确认表 " + table + " 的 schema，停止自动重试");
            return false;
        }
        Integer previous = null;
        try {
            for (int poll = 0; poll < maxColumnPolls; poll++) {
                int current = columnCount(connection, schema, table);
                if (previous != null && previous.intValue() == current) {
                    return true;
                }
                previous = Integer.valueOf(current);
                if (poll + 1 < maxColumnPolls && !pause()) {
                    return false;
                }
            }
            log.warn("表 " + table + " 的列数在等待窗口内仍未稳定，停止自动重试");
            return false;
        } catch (SQLException e) {
            log.warn("读取表 " + table + " 的列数失败，停止自动重试: " + e.getMessage());
            return false;
        }
    }

    private static int columnCount(Connection connection, String schema, String table)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM all_tab_columns WHERE owner = ? AND table_name = ?");
        try {
            statement.setString(1, schema);
            statement.setString(2, table);
            ResultSet resultSet = statement.executeQuery();
            try {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            } finally {
                resultSet.close();
            }
        } finally {
            statement.close();
        }
    }

    private boolean pause() {
        if (columnPollMillis <= 0L) {
            return true;
        }
        try {
            Thread.sleep(columnPollMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
