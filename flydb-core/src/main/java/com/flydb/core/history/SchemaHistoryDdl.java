package com.flydb.core.history;

/**
 * 历史表 DDL 模板（设计 03 §5）。
 *
 * <p>封装各方言家族的历史表与锁表建表 DDL 差异。由 {@link SchemaHistory#ensureExists()} 调用。
 *
 * <p>PG 系/MySQL 系：直接 {@code CREATE TABLE IF NOT EXISTS}（幂等）。
 * Oracle 系：先查系统目录判断存在性，不存在则建表，并捕获"表已存在"错误码兜底。
 */
public interface SchemaHistoryDdl {

    /** 将配置中的原始表名转换为该方言执行 SQL 时使用的标识符。 */
    default String tableName(String rawTableName) {
        return rawTableName;
    }

    /**
     * 返回历史表建表 SQL（含家族特定的列类型与默认值）。
     *
     * @param tableName 表名（默认 {@code flydb_schema_history}）
     */
    String createTableSql(String tableName);

    /**
     * 返回锁表建表 SQL（含家族特定的列类型）。
     *
     * @param lockTableName 锁表名（默认 {@code flydb_schema_lock}）
     */
    String createLockTableSql(String lockTableName);

    /**
     * 返回删表 SQL；{@code tableName} 应与建表使用同一标识符形式。
     * 默认普通 {@code DROP TABLE}，Oracle 家族覆写追加 {@code PURGE} 避免回收站残留。
     */
    default String dropTableSql(String tableName) {
        return "DROP TABLE " + tableName;
    }

    /** PostgreSQL 家族：{@code BOOLEAN}、{@code DEFAULT now()}。 */
    static SchemaHistoryDdl postgresql() {
        return new PostgresqlSchemaHistoryDdl();
    }

    /** MySQL 家族：{@code TINYINT(1)}、{@code DEFAULT CURRENT_TIMESTAMP}。 */
    static SchemaHistoryDdl mysql() {
        return new MysqlSchemaHistoryDdl();
    }

    /** Oracle 家族：{@code NUMBER(1)}、{@code DEFAULT SYSTIMESTAMP}、无 {@code IF NOT EXISTS}。 */
    static SchemaHistoryDdl oracle() {
        return new OracleSchemaHistoryDdl();
    }

    // ---- 实现 ----

    final class PostgresqlSchemaHistoryDdl implements SchemaHistoryDdl {
        @Override
        public String createTableSql(String tableName) {
            return "CREATE TABLE IF NOT EXISTS " + tableName + " (\n"
                    + "    installed_rank INT NOT NULL,\n"
                    + "    version VARCHAR(50),\n"
                    + "    description VARCHAR(200) NOT NULL,\n"
                    + "    type VARCHAR(20) NOT NULL,\n"
                    + "    script VARCHAR(1000) NOT NULL,\n"
                    + "    checksum INT,\n"
                    + "    installed_by VARCHAR(100) NOT NULL,\n"
                    + "    installed_on TIMESTAMP NOT NULL DEFAULT now(),\n"
                    + "    execution_time INT NOT NULL,\n"
                    + "    success BOOLEAN NOT NULL,\n"
                    + "    PRIMARY KEY (installed_rank)\n"
                    + ")";
        }

        @Override
        public String createLockTableSql(String lockTableName) {
            return "CREATE TABLE IF NOT EXISTS " + lockTableName + " (\n"
                    + "    lock_id INT PRIMARY KEY,\n"
                    + "    locked_by VARCHAR(200),\n"
                    + "    locked_at TIMESTAMP\n"
                    + ")";
        }
    }

    final class MysqlSchemaHistoryDdl implements SchemaHistoryDdl {
        @Override
        public String createTableSql(String tableName) {
            return "CREATE TABLE IF NOT EXISTS " + tableName + " (\n"
                    + "    installed_rank INT NOT NULL,\n"
                    + "    version VARCHAR(50),\n"
                    + "    description VARCHAR(200) NOT NULL,\n"
                    + "    type VARCHAR(20) NOT NULL,\n"
                    + "    script VARCHAR(1000) NOT NULL,\n"
                    + "    checksum INT,\n"
                    + "    installed_by VARCHAR(100) NOT NULL,\n"
                    + "    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
                    + "    execution_time INT NOT NULL,\n"
                    + "    success TINYINT(1) NOT NULL,\n"
                    + "    PRIMARY KEY (installed_rank)\n"
                    + ") ENGINE=InnoDB";
        }

        @Override
        public String createLockTableSql(String lockTableName) {
            return "CREATE TABLE IF NOT EXISTS " + lockTableName + " (\n"
                    + "    lock_id INT PRIMARY KEY,\n"
                    + "    locked_by VARCHAR(200),\n"
                    + "    locked_at TIMESTAMP\n"
                    + ") ENGINE=InnoDB";
        }
    }

    final class OracleSchemaHistoryDdl implements SchemaHistoryDdl {
        @Override
        public String createTableSql(String tableName) {
            // Oracle 系无 IF NOT EXISTS，使用 "查系统目录后建表" 的幂等策略
            // Oracle 列定义要求 DEFAULT 子句在 NOT NULL 之前（NOT NULL DEFAULT 顺序报 ORA-00907）
            return "CREATE TABLE " + tableName + " (\n"
                    + "    installed_rank INT NOT NULL,\n"
                    + "    version VARCHAR(50),\n"
                    + "    description VARCHAR(200) NOT NULL,\n"
                    + "    type VARCHAR(20) NOT NULL,\n"
                    + "    script VARCHAR(1000) NOT NULL,\n"
                    + "    checksum INT,\n"
                    + "    installed_by VARCHAR(100) NOT NULL,\n"
                    + "    installed_on TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,\n"
                    + "    execution_time INT NOT NULL,\n"
                    + "    success NUMBER(1) NOT NULL,\n"
                    + "    PRIMARY KEY (installed_rank)\n"
                    + ")";
        }

        @Override
        public String createLockTableSql(String lockTableName) {
            return "CREATE TABLE " + lockTableName + " (\n"
                    + "    lock_id INT PRIMARY KEY,\n"
                    + "    locked_by VARCHAR(200),\n"
                    + "    locked_at TIMESTAMP\n"
                    + ")";
        }

        @Override
        public String dropTableSql(String tableName) {
            return "DROP TABLE " + tableName + " PURGE";
        }
    }
}
