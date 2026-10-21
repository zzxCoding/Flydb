package com.flydb.core.dialect;

import java.sql.Connection;
import java.sql.SQLException;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.history.SchemaHistory;
import com.flydb.core.history.SchemaHistoryDdl;

/** 达梦 DM8 Oracle 兼容家族方言。 */
public final class DmDatabase extends OracleFamilyDatabase {

    private final boolean caseSensitive;

    public DmDatabase(Connection connection, boolean caseSensitive) {
        super("达梦 DM8", connection);
        this.caseSensitive = caseSensitive;
    }

    public boolean caseSensitive() {
        return caseSensitive;
    }

    @Override
    public SchemaHistoryDdl schemaHistoryDdl() {
        final SchemaHistoryDdl delegate = super.schemaHistoryDdl();
        return new SchemaHistoryDdl() {
            @Override
            public String tableName(String rawTableName) {
                return caseSensitive ? quote(rawTableName) : delegate.tableName(rawTableName);
            }

            @Override
            public boolean isTableAlreadyExists(SQLException exception) {
                // DM8 8.1.3.62：对象已存在为 -2124，SQLState 22000 还包含其他错误。
                return exception.getErrorCode() == -2124
                        || delegate.isTableAlreadyExists(exception);
            }

            @Override
            public String createTableSql(String tableName) {
                return delegate.createTableSql(tableName(tableName));
            }

            @Override
            public String createLockTableSql(String lockTableName) {
                return delegate.createLockTableSql(tableName(lockTableName));
            }

            @Override
            public String dropTableSql(String tableName) {
                return delegate.dropTableSql(tableName);
            }
        };
    }

    @Override
    protected String lockTableName(FlydbConfiguration configuration) {
        String lockTable = SchemaHistory.lockTableName(configuration.table());
        return caseSensitive ? quote(lockTable) : lockTable;
    }
}
