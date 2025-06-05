package com.flydb.core.dialect;

public class MySQLDialect implements DatabaseDialect {
    @Override
    public String getCreateVersionTableSql(String tableName) {
        return String.format(
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
            ")", tableName
        );
    }

    @Override
    public String getLatestVersionSql(String tableName) {
        return String.format(
            "SELECT version FROM %s ORDER BY version_rank DESC LIMIT 1",
            tableName
        );
    }

    @Override
    public String getPreviousVersionSql(String tableName, String currentVersion) {
        return String.format(
            "SELECT version FROM %s WHERE version_rank < (SELECT version_rank FROM %s WHERE version = '%s') ORDER BY version_rank DESC LIMIT 1",
            tableName, tableName, currentVersion
        );
    }

    @Override
    public String getVersionsToRollbackSql(String tableName, String targetVersion, String currentVersion) {
        return String.format(
            "SELECT version FROM %s WHERE version > '%s' AND version <= '%s' ORDER BY version_rank DESC",
            tableName, targetVersion, currentVersion
        );
    }
}