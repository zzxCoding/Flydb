package com.flydb.core.dialect;

/**
 * 数据库方言接口，用于处理不同数据库的特定语法
 */
public interface DatabaseDialect {
    /**
     * 获取创建版本控制表的SQL语句
     */
    String getCreateVersionTableSql(String tableName);

    /**
     * 获取查询最新版本的SQL语句
     */
    String getLatestVersionSql(String tableName);

    /**
     * 获取查询上一个版本的SQL语句
     */
    String getPreviousVersionSql(String tableName, String currentVersion);

    /**
     * 获取查询需要回退的版本列表的SQL语句
     */
    String getVersionsToRollbackSql(String tableName, String targetVersion, String currentVersion);
}