package com.flydb.core.executor;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 迁移执行抽象（设计 04 §1.4）。
 *
 * <p>屏蔽 SQL 脚本与 Java 迁移的执行差异：SQL 迁移由 {@link SqlScriptParser} 切分后逐条执行；
 * Java 迁移回调用户实现。每个 {@code ResolvedMigration} 携带自己的执行器。
 *
 * <p>事务边界由命令层（{@code MigrateCommand}）管理，执行器只负责在给定连接上跑语句——
 * 故签名仅取 {@link Connection}（实施时简化自设计草图 {@code execute(Connection, ExecutionContext)}，
 * 因 SQL 路径无需额外上下文；Java 迁移上下文由 {@code JavaMigration} 独立抽象）。
 */
public interface MigrationExecutor {

    /**
     * 在给定连接上执行该迁移。
     *
     * @param connection 已定位到目标 schema 的连接（事务边界由调用方控制）
     * @throws SQLException         驱动原始错误（由调用方决定包裹为 {@code FlydbException}）
     */
    void execute(Connection connection) throws SQLException;
}
