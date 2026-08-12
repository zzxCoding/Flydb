package com.flydb.core.history;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.AppliedMigration;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationVersion;

/**
 * 历史表仓储（设计 03 §5、02 §7）。
 *
 * <p>封装 {@code flydb_schema_history} 的 CRUD：幂等建表、查询全部、插入记录（{@code installed_rank = max + 1}）。
 * 全部 SQL 使用 {@link PreparedStatement} 绑定参数，禁止字符串拼接（旧原型教训）。
 */
public final class SchemaHistory {

    private final String table;
    private final SchemaHistoryDdl ddl;
    private final Connection connection;

    public SchemaHistory(String table, SchemaHistoryDdl ddl, Connection connection) {
        this.table = table;
        this.ddl = ddl;
        this.connection = connection;
    }

    /**
     * 幂等创建历史表 + 锁表。
     *
     * <p>建表动作发生在获取锁<b>之前</b>（锁表本身需要先存在），因此建表必须自身幂等且容忍竞态。
     * 调用者应确保在获取锁之后调用此方法。
     */
    public void ensureExists() {
        executeCreateTable(ddl.createTableSql(table));
        executeCreateTable(ddl.createLockTableSql(lockTableName(table)));
        ensureLockRow();
    }

    /** 历史表对应的锁表名；默认 history → lock。 */
    public static String lockTableName(String historyTable) {
        return historyTable.endsWith("_history")
                ? historyTable.substring(0, historyTable.length() - "_history".length()) + "_lock"
                : historyTable + "_lock";
    }

    private void ensureLockRow() {
        String sql = "INSERT INTO " + lockTable() + " (lock_id) VALUES (?)";
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            if (statement == null) {
                return;
            }
            statement.setInt(1, 1);
            statement.executeUpdate();
        } catch (SQLException ignored) {
            // 固定行已存在；互斥正确性由数据库行锁保证。
        } finally {
            closeQuietly(statement);
        }
    }

    private void executeCreateTable(String sql) {
        Statement stmt = null;
        try {
            stmt = connection.createStatement();
            stmt.execute(sql);
        } catch (SQLException e) {
            // Oracle 系无 IF NOT EXISTS：表已存在错误码（ORA-00955）→ 幂等忽略
            // 其他系直接用 IF NOT EXISTS 无此问题
            if (e.getErrorCode() != 955) { // ORA-00955: name is already used by an existing object
                throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                        "建表失败: " + e.getMessage(), e);
            }
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /**
     * 查询历史表全部记录，按 installed_rank 升序。
     */
    public List<AppliedMigration> findAll() {
        List<AppliedMigration> records = new ArrayList<AppliedMigration>();
        String sql = "SELECT installed_rank, version, description, type, script, checksum, "
                + "installed_by, installed_on, execution_time, success "
                + "FROM " + historyTable() + " ORDER BY installed_rank";
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            rs = stmt.executeQuery(sql);
            while (rs.next()) {
                String versionStr = rs.getString("version");
                MigrationVersion version = versionStr != null && !versionStr.isEmpty()
                        ? MigrationVersion.parse(versionStr) : null;
                records.add(AppliedMigration.of(
                        rs.getInt("installed_rank"),
                        version,
                        rs.getString("description"),
                        MigrationType.valueOf(rs.getString("type")),
                        rs.getString("script"),
                        rs.getObject("checksum") != null ? rs.getInt("checksum") : null,
                        rs.getString("installed_by"),
                        rs.getTimestamp("installed_on"),
                        rs.getInt("execution_time"),
                        rs.getBoolean("success")));
            }
        } catch (SQLException e) {
            // 历史表不存在时返回空列表（info 首次使用场景）
            return records;
        } finally {
            closeQuietly(rs, stmt);
        }
        return records;
    }

    /**
     * 插入一条迁移记录（{@code installed_rank = max + 1}）。
     */
    public void insert(AppliedMigration migration) {
        int nextRank = nextInstalledRank();
        String sql = "INSERT INTO " + historyTable()
                + " (installed_rank, version, description, type, script, checksum, "
                + "installed_by, installed_on, execution_time, success) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        PreparedStatement ps = null;
        try {
            ps = connection.prepareStatement(sql);
            ps.setInt(1, nextRank);
            if (migration.version() != null) {
                ps.setString(2, migration.version().toString());
            } else {
                ps.setNull(2, java.sql.Types.VARCHAR);
            }
            ps.setString(3, migration.description());
            ps.setString(4, migration.type().name());
            ps.setString(5, migration.script());
            if (migration.checksum() != null) {
                ps.setInt(6, migration.checksum());
            } else {
                ps.setNull(6, java.sql.Types.INTEGER);
            }
            ps.setString(7, migration.installedBy());
            ps.setTimestamp(8, migration.installedOn());
            ps.setInt(9, migration.executionTimeMillis());
            ps.setBoolean(10, migration.success());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "插入历史记录失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(ps);
        }
    }

    /** 删除全部失败记录并返回被清除的脚本名。 */
    public List<String> deleteFailed(List<AppliedMigration> records) {
        List<String> removed = new ArrayList<String>();
        for (AppliedMigration record : records) {
            if (!record.success()) {
                removed.add(record.script());
            }
        }
        if (removed.isEmpty()) {
            return removed;
        }
        String sql = "DELETE FROM " + historyTable() + " WHERE success = ?";
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            statement.setBoolean(1, false);
            statement.executeUpdate();
            return removed;
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "清除失败历史记录失败: " + e.getMessage(), e);
        } finally {
            closeQuietly(statement);
        }
    }

    /** 按脚本名对齐 checksum。 */
    public void updateChecksum(String script, Integer checksum) {
        String sql = "UPDATE " + historyTable()
                + " SET checksum = ? WHERE script = ? AND success = ?";
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            if (checksum == null) {
                statement.setNull(1, java.sql.Types.INTEGER);
            } else {
                statement.setInt(1, checksum);
            }
            statement.setString(2, script);
            statement.setBoolean(3, true);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "对齐 checksum 失败: " + script + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(statement);
        }
    }

    private int nextInstalledRank() {
        String sql = "SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM " + historyTable();
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = connection.createStatement();
            rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 1;
        } catch (SQLException e) {
            return 1; // 表不存在则从 1 开始
        } finally {
            closeQuietly(rs, stmt);
        }
    }

    private static void closeQuietly(AutoCloseable... closeables) {
        for (AutoCloseable c : closeables) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String historyTable() {
        return ddl.tableName(table);
    }

    private String lockTable() {
        return ddl.tableName(lockTableName(table));
    }
}
