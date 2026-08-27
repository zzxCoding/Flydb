package com.flydb.core.dialect;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flydb.core.log.Log;
import com.flydb.core.log.LogCreator;
import com.flydb.core.log.LogFactory;
import com.flydb.core.test.JdbcFakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Oracle 家族 clean 策略")
class OracleCleanStrategyTest {

    private final List<String> logs = new ArrayList<String>();

    @AfterEach
    void resetLogFactory() {
        LogFactory.setLogCreator(null);
    }

    @Test
    @DisplayName("表带 PURGE 删除，序列查 all_sequences 并跳过 ISEQ$$_/BIN$，记账表忽略大小写排除")
    void dropsTablesWithPurgeAndDiscoversSchemaSequences() throws Exception {
        LogFactory.setLogCreator(recording());
        List<String> sql = new ArrayList<String>();
        Map<String, List<String>> objects = new HashMap<String, List<String>>();
        objects.put("VIEW", Arrays.asList("V_SUMMARY"));
        // Oracle 非引用名按大写返回；排除名单是小写配置名
        objects.put("TABLE", Arrays.asList("APP_TABLE", "FLYDB_SCHEMA_HISTORY"));
        Connection connection = oracleConnection(sql, objects,
                Arrays.asList("SEQ_ORDER", "ISEQ$$_990081", "BIN$legacy==0"), null);

        new OracleCleanStrategy().clean(connection, "APP",
                Arrays.asList("flydb_schema_history", "flydb_schema_lock"));

        assertThat(sql).containsExactly(
                "DROP VIEW \"V_SUMMARY\"",
                "DROP TABLE \"APP_TABLE\" PURGE",
                "DROP SEQUENCE \"SEQ_ORDER\"",
                "PURGE RECYCLEBIN");
        assertThat(logs).contains(
                "发现待清理对象：视图 1，表 1，序列 1",
                "正在删除表 1/1: APP_TABLE",
                "正在删除序列 1/1: SEQ_ORDER",
                "回收站已清空");
    }

    @Test
    @DisplayName("序列按待清理 schema 枚举，而不是按登录用户枚举")
    void discoversSequencesFromCurrentSchemaWhenLoginUserDiffers() throws Exception {
        List<String> sql = new ArrayList<String>();
        Map<String, List<String>> objects = new HashMap<String, List<String>>();
        objects.put("VIEW", new ArrayList<String>());
        objects.put("TABLE", new ArrayList<String>());
        Connection connection = oracleConnection(sql, objects,
                Arrays.asList("SEQ_APP"), "APP", null);

        new OracleCleanStrategy().clean(connection, "APP",
                Arrays.asList("flydb_schema_history", "flydb_schema_lock"));

        assertThat(sql).containsExactly(
                "DROP SEQUENCE \"SEQ_APP\"",
                "PURGE RECYCLEBIN");
    }

    @Test
    @DisplayName("schema 未显式传入时仍使用 all_sequences 与 CURRENT_SCHEMA")
    void discoversSequencesFromCurrentSchemaContextWhenSchemaIsNull() throws Exception {
        List<String> sql = new ArrayList<String>();
        Map<String, List<String>> objects = new HashMap<String, List<String>>();
        objects.put("VIEW", new ArrayList<String>());
        objects.put("TABLE", new ArrayList<String>());
        Connection connection = oracleConnection(sql, objects,
                Arrays.asList("SEQ_CONTEXT"), null, true, null);

        new OracleCleanStrategy().clean(connection, null,
                Arrays.asList("flydb_schema_history", "flydb_schema_lock"));

        assertThat(sql).containsExactly(
                "DROP SEQUENCE \"SEQ_CONTEXT\"",
                "PURGE RECYCLEBIN");
    }

    @Test
    @DisplayName("PURGE RECYCLEBIN 失败降级为警告，不阻断 clean")
    void purgeFailureDegradesToWarning() {
        LogFactory.setLogCreator(recording());
        List<String> sql = new ArrayList<String>();
        Map<String, List<String>> objects = new HashMap<String, List<String>>();
        objects.put("VIEW", new ArrayList<String>());
        objects.put("TABLE", new ArrayList<String>());
        Connection connection = oracleConnection(sql, objects,
                new ArrayList<String>(), "PURGE RECYCLEBIN");

        assertThatCode(() -> new OracleCleanStrategy().clean(connection, "APP",
                Arrays.asList("flydb_schema_history")))
                .doesNotThrowAnyException();

        assertThat(sql).containsExactly("PURGE RECYCLEBIN");
        assertThat(logs).anyMatch(message ->
                message.contains("PURGE RECYCLEBIN 失败") && message.contains("已跳过"));
    }

    @Test
    @DisplayName("OB 驱动用 ORA-00600 主码包装 -4007 时仍等待列数稳定并重试")
    void retriesWrappedObDdlInProgressAfterColumnCountStabilizes() throws Exception {
        LogFactory.setLogCreator(recording());
        List<String> sql = new ArrayList<String>();
        int[] columnQueries = new int[1];
        Connection connection = transientTableDropConnection(sql, 600,
                "ORA-00600: internal error code, arguments: -4007, [DDL in progress]",
                1, new int[]{5, 4, 4}, columnQueries);

        new OracleCleanStrategy(3, 4, 0L).clean(connection, "APP",
                Arrays.asList("flydb_schema_history", "flydb_schema_lock"));

        assertThat(Collections.frequency(sql, "DROP TABLE \"BUSY_TABLE\" PURGE"))
                .isEqualTo(2);
        assertThat(columnQueries[0]).isEqualTo(3);
        assertThat(sql).endsWith("PURGE RECYCLEBIN");
        assertThat(logs).anyMatch(message ->
                message.contains("BUSY_TABLE") && message.contains("DDL 仍在进行")
                        && message.contains("重试"));
    }

    @Test
    @DisplayName("OB DDL 产生的隐藏中间表不进入 clean 删除清单")
    void skipsObHiddenDdlTables() throws Exception {
        List<String> sql = new ArrayList<String>();
        Map<String, List<String>> objects = new HashMap<String, List<String>>();
        objects.put("VIEW", new ArrayList<String>());
        objects.put("TABLE", Arrays.asList(
                "_OB_HIDDEN_2403021_TABLE_SCHEMA", "APP_TABLE", "HIDDEN_AUDIT"));
        Connection connection = oracleConnection(sql, objects,
                new ArrayList<String>(), null);

        new OracleCleanStrategy().clean(connection, "APP",
                Arrays.asList("flydb_schema_history", "flydb_schema_lock"));

        assertThat(sql).containsExactly(
                "DROP TABLE \"APP_TABLE\" PURGE",
                "DROP TABLE \"HIDDEN_AUDIT\" PURGE",
                "PURGE RECYCLEBIN");
    }

    @Test
    @DisplayName("ORA-00600 包装其他内部错误时不重试")
    void doesNotRetryOtherTableDropFailures() {
        List<String> sql = new ArrayList<String>();
        int[] columnQueries = new int[1];
        Connection connection = transientTableDropConnection(sql, 600,
                "ORA-00600: internal error code, arguments: -4016, [unexpected]",
                1, new int[]{5, 5}, columnQueries);

        assertThatThrownBy(() -> new OracleCleanStrategy().clean(connection, "APP",
                Arrays.asList("flydb_schema_history", "flydb_schema_lock")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("-4016");

        assertThat(Collections.frequency(sql, "DROP TABLE \"BUSY_TABLE\" PURGE"))
                .isEqualTo(1);
        assertThat(columnQueries[0]).isZero();
    }

    @Test
    @DisplayName("OB -4007 持续出现时只做有限次数重试")
    void stopsAfterBoundedObDdlRetries() {
        List<String> sql = new ArrayList<String>();
        int[] columnQueries = new int[1];
        Connection connection = transientTableDropConnection(sql, -4007, 3,
                new int[]{5, 5, 5, 5}, columnQueries);

        assertThatThrownBy(() -> new OracleCleanStrategy(3, 4, 0L)
                .clean(connection, "APP",
                        Arrays.asList("flydb_schema_history", "flydb_schema_lock")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("OBE-4007");

        assertThat(Collections.frequency(sql, "DROP TABLE \"BUSY_TABLE\" PURGE"))
                .isEqualTo(3);
        assertThat(columnQueries[0]).isEqualTo(4);
    }

    private LogCreator recording() {
        return clazz -> new Log() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { logs.add(message); }
            @Override public void warn(String message) { logs.add(message); }
            @Override public void error(String message, Throwable error) { }
        };
    }

    /** 模拟 Oracle 目录的连接：getTables 按类型返回对象，序列视图返回序列，execute 记录 SQL。 */
    private static Connection oracleConnection(final List<String> captured,
                                               final Map<String, List<String>> objects,
                                               final List<String> sequences,
                                               final String failOnSql) {
        return oracleConnection(captured, objects, sequences, null, failOnSql);
    }

    private static Connection oracleConnection(final List<String> captured,
                                               final Map<String, List<String>> objects,
                                               final List<String> sequences,
                                               final String requiredSequenceOwner,
                                               final String failOnSql) {
        return oracleConnection(captured, objects, sequences, requiredSequenceOwner,
                false, failOnSql);
    }

    private static Connection oracleConnection(final List<String> captured,
                                               final Map<String, List<String>> objects,
                                               final List<String> sequences,
                                               final String requiredSequenceOwner,
                                               final boolean requireCurrentSchemaContext,
                                               final String failOnSql) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getMetaData".equals(name)) {
                        return Proxy.newProxyInstance(
                                java.sql.DatabaseMetaData.class.getClassLoader(),
                                new Class<?>[]{java.sql.DatabaseMetaData.class},
                                (meta, metaMethod, metaArgs) -> {
                                    if ("getTables".equals(metaMethod.getName())) {
                                        List<String> rows = new ArrayList<String>();
                                        for (String type : (String[]) metaArgs[3]) {
                                            List<String> found = objects.get(type);
                                            if (found != null) rows.addAll(found);
                                        }
                                        return rowsResultSet(rows);
                                    }
                                    return JdbcFakes.defaultValue(metaMethod.getReturnType());
                                });
                    }
                    if ("createStatement".equals(name)) {
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                new Class<?>[]{Statement.class},
                                (statement, statementMethod, statementArgs) -> {
                                    if ("execute".equals(statementMethod.getName())
                                            && statementArgs != null) {
                                        String executed = (String) statementArgs[0];
                                        captured.add(executed);
                                        if (failOnSql != null && executed.equals(failOnSql)) {
                                            throw new SQLException("ORA-38301: 回收站功能未启用");
                                        }
                                        return false;
                                    }
                                    if ("close".equals(statementMethod.getName())) return null;
                                    return JdbcFakes.defaultValue(statementMethod.getReturnType());
                                });
                    }
                    if ("prepareStatement".equals(name)) {
                        final String preparedSql = (String) args[0];
                        final String[] sequenceOwner = new String[1];
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class},
                                (prepared, preparedMethod, preparedArgs) -> {
                                    if ("setString".equals(preparedMethod.getName())) {
                                        sequenceOwner[0] = (String) preparedArgs[1];
                                        return null;
                                    }
                                    if ("executeQuery".equals(preparedMethod.getName())) {
                                        if (requireCurrentSchemaContext
                                                && (!preparedSql.contains("FROM all_sequences")
                                                || !preparedSql.contains("SYS_CONTEXT"))) {
                                            return rowsResultSet(new ArrayList<String>());
                                        }
                                        if (requiredSequenceOwner != null
                                                && (!preparedSql.contains("FROM all_sequences")
                                                || !requiredSequenceOwner.equals(sequenceOwner[0]))) {
                                            return rowsResultSet(new ArrayList<String>());
                                        }
                                        return rowsResultSet(sequences);
                                    }
                                    if ("close".equals(preparedMethod.getName())) return null;
                                    return JdbcFakes.defaultValue(preparedMethod.getReturnType());
                                });
                    }
                    if ("close".equals(name) || "isClosed".equals(name)) {
                        return "isClosed".equals(name) ? false : null;
                    }
                    return JdbcFakes.defaultValue(method.getReturnType());
                });
    }

    private static ResultSet rowsResultSet(final List<String> rows) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, new java.lang.reflect.InvocationHandler() {
                    private int index = -1;

                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method,
                                         Object[] args) {
                        String name = method.getName();
                        if ("next".equals(name)) {
                            return ++index < rows.size();
                        }
                        if ("getString".equals(name) && args != null && args.length > 0) {
                            return index >= 0 ? rows.get(index) : null;
                        }
                        if ("close".equals(name)) {
                            return null;
                        }
                        return JdbcFakes.defaultValue(method.getReturnType());
                    }
                });
    }

    private static Connection transientTableDropConnection(final List<String> captured,
                                                           final int errorCode,
                                                           final int failures,
                                                           final int[] columnCounts,
                                                           final int[] columnQueries) {
        return transientTableDropConnection(captured, errorCode,
                "OBE" + errorCode + ": object is locked by concurrent DDL",
                failures, columnCounts, columnQueries);
    }

    private static Connection transientTableDropConnection(final List<String> captured,
                                                           final int errorCode,
                                                           final String errorMessage,
                                                           final int failures,
                                                           final int[] columnCounts,
                                                           final int[] columnQueries) {
        final int[] dropAttempts = new int[1];
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                                new Class<?>[]{DatabaseMetaData.class},
                                (metadata, metadataMethod, metadataArgs) -> {
                                    if ("getTables".equals(metadataMethod.getName())) {
                                        String type = ((String[]) metadataArgs[3])[0];
                                        return rowsResultSet("TABLE".equals(type)
                                                ? Arrays.asList("BUSY_TABLE")
                                                : new ArrayList<String>());
                                    }
                                    return JdbcFakes.defaultValue(metadataMethod.getReturnType());
                                });
                    }
                    if ("createStatement".equals(method.getName())) {
                        return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                                new Class<?>[]{Statement.class},
                                (statement, statementMethod, statementArgs) -> {
                                    if ("execute".equals(statementMethod.getName())) {
                                        String executed = (String) statementArgs[0];
                                        captured.add(executed);
                                        if (executed.startsWith("DROP TABLE")
                                                && dropAttempts[0]++ < failures) {
                                            throw new SQLException(errorMessage, "HY000",
                                                    errorCode);
                                        }
                                        return false;
                                    }
                                    return JdbcFakes.defaultValue(statementMethod.getReturnType());
                                });
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        final String preparedSql = (String) args[0];
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class},
                                (statement, statementMethod, statementArgs) -> {
                                    if ("executeQuery".equals(statementMethod.getName())) {
                                        if (preparedSql.contains("all_tab_columns")) {
                                            int index = Math.min(columnQueries[0]++,
                                                    columnCounts.length - 1);
                                            return countResultSet(columnCounts[index]);
                                        }
                                        return rowsResultSet(new ArrayList<String>());
                                    }
                                    return JdbcFakes.defaultValue(statementMethod.getReturnType());
                                });
                    }
                    return JdbcFakes.defaultValue(method.getReturnType());
                });
    }

    private static ResultSet countResultSet(final int count) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, new java.lang.reflect.InvocationHandler() {
                    private boolean beforeFirst = true;

                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method,
                                         Object[] args) {
                        if ("next".equals(method.getName())) {
                            boolean result = beforeFirst;
                            beforeFirst = false;
                            return result;
                        }
                        if ("getInt".equals(method.getName())) return count;
                        return JdbcFakes.defaultValue(method.getReturnType());
                    }
                });
    }
}
