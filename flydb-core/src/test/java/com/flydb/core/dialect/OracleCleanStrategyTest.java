package com.flydb.core.dialect;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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

@DisplayName("Oracle 家族 clean 策略")
class OracleCleanStrategyTest {

    private final List<String> logs = new ArrayList<String>();

    @AfterEach
    void resetLogFactory() {
        LogFactory.setLogCreator(null);
    }

    @Test
    @DisplayName("表带 PURGE 删除，序列查 user_sequences 并跳过 ISEQ$$_/BIN$，记账表忽略大小写排除")
    void dropsTablesWithPurgeAndDiscoversUserSequences() throws Exception {
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

    private LogCreator recording() {
        return clazz -> new Log() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { logs.add(message); }
            @Override public void warn(String message) { logs.add(message); }
            @Override public void error(String message, Throwable error) { }
        };
    }

    /** 模拟 Oracle 目录的连接：getTables 按类型返回对象，user_sequences 返回序列，execute 记录 SQL。 */
    private static Connection oracleConnection(final List<String> captured,
                                               final Map<String, List<String>> objects,
                                               final List<String> sequences,
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
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class},
                                (prepared, preparedMethod, preparedArgs) -> {
                                    if ("executeQuery".equals(preparedMethod.getName())) {
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
}
