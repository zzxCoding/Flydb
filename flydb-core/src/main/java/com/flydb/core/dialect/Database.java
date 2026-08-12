package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.executor.SqlStatementBuilderConfig;
import com.flydb.core.history.SchemaHistoryDdl;
import com.flydb.core.lock.MigrationLock;

/**
 * 数据库方言（设计 03 §2）。
 *
 * <p>一个 {@code Database} 实例绑定一条连接会话，封装方言差异。{@link AutoCloseable} 标记其生命周期
 * 受连接管理。
 */
public interface Database extends AutoCloseable {

    /** 展示名，如 "达梦 DM8"。 */
    String name();

    /** 是否支持 DDL 事务——决定 migrate 失败处理策略（设计 04 §3）。 */
    boolean supportsDdlTransactions();

    /** 标识符引用（含转义），如 {@code "orders"} 或 {@code `orders`}。 */
    String quote(String identifier);

    /** 当前 Schema 名称。 */
    String currentSchema() throws SQLException;

    /**
     * 当前数据库用户（写入 {@code installed_by}）。
     * 为 {@link #currentUser()} 返回 {@code flydb_schema_history.installed_by} 值。
     */
    String currentUser() throws SQLException;

    /**
     * 语句切分器配置（设计 04 §1.2）。
     * 返回的配置应能在该方言的迁移脚本上正确切分。
     */
    SqlStatementBuilderConfig statementBuilderConfig();

    /** 当前家族的历史表/锁表 DDL。 */
    SchemaHistoryDdl schemaHistoryDdl();

    /** 创建由独立连接持有的命令级迁移锁。 */
    MigrationLock createLock(FlydbConfiguration configuration);

    /** 当前家族的 clean 策略。 */
    CleanStrategy cleanStrategy();

    /**
     * 获取当前会话的 schema 名称。
     *
     * @return 当前 schema 名
     */
    @Override
    void close() throws Exception;
}
