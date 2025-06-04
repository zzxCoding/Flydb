package com.flydb.core;

import java.io.IOException;
import java.sql.Connection;
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
    private final Connection connection;
    public static final String VERSION_TABLE = "flydb_schema_history";
    
    public FlyDB(Connection connection) {
        this.connection = connection;
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
            // 如果没有指定目标版本，获取上一个版本
            try (Statement stmt = connection.createStatement()) {
                String sql = String.format(
                    "SELECT version FROM %s WHERE version_rank < (SELECT version_rank FROM %s WHERE version = '%s') ORDER BY version_rank DESC LIMIT 1",
                    VERSION_TABLE, VERSION_TABLE, currentVersion
                );
                ResultSet rs = stmt.executeQuery(sql);
                if (rs.next()) {
                    rollbackVersion = rs.getString("version");
                } else {
                    throw new SQLException("没有可回退的版本");
                }
            }
        }

        // 开始事务
        connection.setAutoCommit(false);
        try {
            // 获取需要回退的版本列表
            List<String> versionsToRollback = new ArrayList<>();
            try (Statement stmt = connection.createStatement()) {
                String sql = String.format(
                    "SELECT version FROM %s WHERE version > '%s' AND version <= '%s' ORDER BY version_rank DESC",
                    VERSION_TABLE, rollbackVersion, currentVersion
                );
                ResultSet rs = stmt.executeQuery(sql);
                while (rs.next()) {
                    versionsToRollback.add(rs.getString("version"));
                }
            }

            // 执行回退脚本
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

            // 删除版本记录
            try (Statement stmt = connection.createStatement()) {
                String deleteSql = String.format(
                    "DELETE FROM %s WHERE version > '%s'",
                    VERSION_TABLE, rollbackVersion
                );
                stmt.execute(deleteSql);
            }

            // 提交事务
            connection.commit();
        } catch (SQLException e) {
            // 发生错误时回滚事务
            connection.rollback();
            throw new SQLException("回退操作失败: " + e.getMessage(), e);
        } finally {
            // 恢复自动提交
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
            String createTableSql = String.format(
                "CREATE TABLE IF NOT EXISTS %s (" +
                "version_rank INT NOT NULL, " +
                "installed_rank INT NOT NULL, " +
                "version VARCHAR(50) NOT NULL, " +
                "description VARCHAR(200) NOT NULL, " +
                "type VARCHAR(20) NOT NULL, " +
                "script VARCHAR(1000) NOT NULL, " +
                "checksum INT, " +
                "installed_by VARCHAR(100) NOT NULL, " +
                "installed_on TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "execution_time INT NOT NULL, " +
                "success BOOLEAN NOT NULL, " +
                "PRIMARY KEY (version)" +
                ")", VERSION_TABLE
            );
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
            String sql = String.format(
                "SELECT version FROM %s ORDER BY version_rank DESC LIMIT 1",
                VERSION_TABLE
            );
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getString("version");
            }
            return "0";
        }
    }
}