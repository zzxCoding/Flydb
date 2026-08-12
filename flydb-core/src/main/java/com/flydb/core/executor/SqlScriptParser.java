package com.flydb.core.executor;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * SQL 脚本解析器：把整份脚本切分为有序的 {@link SqlStatement} 列表。
 *
 * <p>对外入口，封装 {@link SqlScriptLexer}（字符级状态机）+ 家族配置。{@link SqlMigrationResolver}
 * 读取脚本原文后调用本类得到语句列表，再由 {@code MigrationExecutor} 逐条执行（设计 04 §1.4）。
 *
 * <p>返回的列表不可变；空脚本返回空列表。
 */
public final class SqlScriptParser {

    private final SqlStatementBuilderConfig config;

    /**
     * @param config 家族配置（决定引号/转义/注释/dollar-quoting/PL/SQL/DELIMITER 行为）
     */
    public SqlScriptParser(SqlStatementBuilderConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * 切分脚本。
     *
     * @param sqlScript 脚本原文（不能为 null）
     * @return 不可变的语句列表（已丢弃纯注释/空白切出的空语句）
     */
    public List<SqlStatement> parse(String sqlScript) {
        Objects.requireNonNull(sqlScript, "sqlScript must not be null");
        List<SqlStatement> statements = new SqlScriptLexer(config).tokenize(sqlScript);
        return Collections.unmodifiableList(statements);
    }
}
