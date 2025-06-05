package com.flydb.core.dialect;

public class OracleDialect implements DatabaseDialect {
    @Override
    public String getCreateVersionTableSql(String tableName) {
        return String.format(
            "CREATE TABLE %s (" +
            "version_rank NUMBER NOT NULL, " +
            "installed_rank NUMBER NOT NULL, " +
            "version VARCHAR2(50) NOT NULL, " +
            "description VARCHAR2(200) NOT NULL, " +
            "type VARCHAR2(20) NOT NULL, " +
            "script VARCHAR2(1000) NOT NULL, " +
            "checksum NUMBER, " +
            "installed_by VARCHAR2(100) NOT NULL, " +
            "installed_on TIMESTAMP DEFAULT SYSTIMESTAMP, " +
            "execution_time NUMBER NOT NULL, " +
            "success NUMBER(1) NOT NULL, " +
            "CONSTRAINT pk_%s PRIMARY KEY (version)" +
            ")", tableName, tableName
        );
    }

    @Override
    public String getLatestVersionSql(String tableName) {
        return String.format(
            "SELECT version FROM (SELECT version FROM %s ORDER BY version_rank DESC) WHERE ROWNUM = 1",
            tableName
        );
    }

    @Override
    public String getPreviousVersionSql(String tableName, String currentVersion) {
        return String.format(
            "SELECT version FROM (SELECT version FROM %s WHERE version_rank < (SELECT version_rank FROM %s WHERE version = '%s') ORDER BY version_rank DESC) WHERE ROWNUM = 1",
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