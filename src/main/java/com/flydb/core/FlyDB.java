package com.flydb.core;

import com.flydb.core.dialect.DatabaseDialect;
import com.flydb.core.dialect.MySQLDialect;
import com.flydb.core.dialect.OracleDialect;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * FlyDB核心类，负责数据库版本管理和迁移
 * 该类提供了数据库版本控制的核心功能，包括：
 * - 初始化版本控制表
 * - 获取当前数据库版本
 * - 执行版本回退操作
 * 
 * 版本控制表(flydb_schema_history)用于记录所有的数据库变更历史，
 * 包括版本号、描述、执行时间、执行状态等信息。
 */
public class FlyDB {
    /** 数据库连接对象 */
    private final Connection connection;
    
    /** 数据库方言对象，用于处理不同数据库的特定语法 */
    private final DatabaseDialect dialect;
    
    /** 版本控制表名 */
    public static final String VERSION_TABLE = "flydb_schema_history";
    
    /**
     * 构造函数
     * 
     * @param connection 数据库连接对象
     * @throws SQLException 当无法确定数据库方言时抛出
     */
    public FlyDB(Connection connection) throws SQLException {
        this.connection = connection;
        this.dialect = determineDialect(connection);
    }

    /**
     * 根据数据库连接信息确定使用的数据库方言
     * 
     * @param connection 数据库连接对象
     * @return 对应的数据库方言实现
     * @throws SQLException 当无法获取数据库元数据时抛出
     */
    private DatabaseDialect determineDialect(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        String databaseProductName = metaData.getDatabaseProductName().toLowerCase();
        
        if (databaseProductName.contains("oracle")) {
            return new OracleDialect();
        } else {
            // 默认使用MySQL方言
            return new MySQLDialect();
        }
    }

    /**
     * 回退到指定版本
     * 
     * @param targetVersion 目标版本号，如果为null则回退到上一个版本
     * @throws SQLException 当回退操作失败时抛出，可能的原因包括：
     *                     - 当前数据库没有任何版本记录
     *                     - 没有可回退的版本
     *                     - 执行回退脚本时发生错误
     */
    public void rollback(String targetVersion) throws SQLException {
        String currentVersion = getCurrentVersion();
        if (currentVersion.equals("0")) {
            throw new SQLException("当前数据库没有任何版本记录，无法执行回退操作");
        }

        String rollbackVersion = targetVersion;
        if (rollbackVersion == null) {
            try (Statement stmt = connection.createStatement()) {
                String sql = dialect.getPreviousVersionSql(VERSION_TABLE, currentVersion);
                ResultSet rs = stmt.executeQuery(sql);
                if (rs.next()) {
                    rollbackVersion = rs.getString("version");
                } else {
                    throw new SQLException("没有可回退的版本");
                }
            }
        }

        connection.setAutoCommit(false);
        try {
            List<String> versionsToRollback = new ArrayList<>();
            try (Statement stmt = connection.createStatement()) {
                String sql = dialect.getVersionsToRollbackSql(VERSION_TABLE, rollbackVersion, currentVersion);
                ResultSet rs = stmt.executeQuery(sql);
                while (rs.next()) {
                    versionsToRollback.add(rs.getString("version"));
                }
            }

            Migration migration = new Migration(connection, "db/migration");
            for (String version : versionsToRollback) {
                try {
                    String rollbackScript = migration.loadRollbackScript(version);
                    if (rollbackScript == null) {
                        throw new SQLException("找不到版本 " + version + " 的回退脚本");
                    }
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute(rollbackScript);
                    }
                } catch (IOException e) {
                    throw new SQLException("加载回退脚本失败: " + e.getMessage(), e);
                }
            }

            try (Statement stmt = connection.createStatement()) {
                String deleteSql = String.format(
                    "DELETE FROM %s WHERE version > '%s'",
                    VERSION_TABLE, rollbackVersion
                );
                stmt.execute(deleteSql);
            }

            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw new SQLException("回退操作失败: " + e.getMessage(), e);
        } finally {
            connection.setAutoCommit(true);
        }
    }
    
    /**
     * 初始化版本控制表
     * 创建flydb_schema_history表，用于记录数据库变更历史。
     * 表结构包含以下字段：
     * - version_rank: 版本排序号
     * - installed_rank: 安装排序号
     * - version: 版本号
     * - description: 变更描述
     * - type: 变更类型
     * - script: 执行的SQL脚本
     * - checksum: 脚本校验和
     * - installed_by: 执行人
     * - installed_on: 执行时间
     * - execution_time: 执行耗时
     * - success: 是否执行成功
     * 
     * @throws SQLException 当创建表失败时抛出
     */
    public void init() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            String createTableSql = dialect.getCreateVersionTableSql(VERSION_TABLE);
            stmt.execute(createTableSql);
        }
    }
    
    /**
     * 获取当前数据库版本
     * 
     * @return 当前数据库版本号，如果没有任何版本记录则返回"0"
     * @throws SQLException 当查询版本信息失败时抛出
     */
    public String getCurrentVersion() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            String sql = dialect.getLatestVersionSql(VERSION_TABLE);
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getString("version");
            }
            return "0";
        }
    }
}