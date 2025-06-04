package com.flydb.core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迁移脚本管理类
 * 负责管理和执行数据库迁移脚本，主要功能包括：
 * - 加载迁移脚本文件
 * - 解析脚本版本号
 * - 执行迁移脚本
 * - 记录迁移历史
 */
public class Migration {
    private static final Pattern VERSION_PATTERN = Pattern.compile("V(\\d+)__.*\\.sql");
    private static final Pattern ROLLBACK_PATTERN = Pattern.compile("R(\\d+)__.*\\.sql");
    private final Connection connection;
    private final String scriptPath;
    
    public Migration(Connection connection, String scriptPath) {
        this.connection = connection;
        this.scriptPath = scriptPath;
    }
    
    /**
     * 加载迁移脚本
     * 从指定目录加载所有SQL迁移脚本，并按版本号排序
     * 
     * @return 迁移脚本列表
     * @throws IOException 当脚本目录不存在或无法读取脚本文件时抛出
     */
    public List<MigrationScript> loadMigrationScripts() throws IOException {
        List<MigrationScript> scripts = new ArrayList<>();
        File scriptsDir;
        
        // 判断是否是绝对路径
        if (scriptPath.startsWith("/") || scriptPath.contains(":")) {
            scriptsDir = new File(scriptPath);
        } else {
            // 相对路径时，从classpath或jar中读取
            URL resourceUrl = getClass().getClassLoader().getResource(scriptPath);
            if (resourceUrl == null) {
                throw new IOException("Migration scripts directory not found: " + scriptPath);
            }
            scriptsDir = new File(resourceUrl.getFile());
        }
        
        if (!scriptsDir.exists() || !scriptsDir.isDirectory()) {
            throw new IOException("Migration scripts directory not found: " + scriptPath);
        }
        
        Files.walk(scriptsDir.toPath())
            .filter(path -> path.toString().endsWith(".sql"))
            .forEach(path -> {
                String version = extractVersion(path.getFileName().toString());
                if (version != null) {
                    try {
                        String sql = new String(Files.readAllBytes(path));
                        scripts.add(new MigrationScript(version, path.getFileName().toString(), sql));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read migration script: " + path, e);
                    }
                }
            });
        
        return scripts;
    }

    /**
     * 加载回退脚本
     * 从指定目录加载指定版本的回退脚本
     * 
     * @param version 需要回退的版本号
     * @return 回退脚本，如果不存在则返回null
     * @throws IOException 当脚本目录不存在或无法读取脚本文件时抛出
     */
    public String loadRollbackScript(String version) throws IOException {
        File scriptsDir = new File(scriptPath);
        if (!scriptsDir.exists() || !scriptsDir.isDirectory()) {
            throw new IOException("Migration scripts directory not found: " + scriptPath);
        }
        
        return Files.walk(scriptsDir.toPath())
            .filter(path -> path.toString().endsWith(".sql"))
            .filter(path -> {
                String fileName = path.getFileName().toString();
                Matcher matcher = ROLLBACK_PATTERN.matcher(fileName);
                return matcher.matches() && matcher.group(1).equals(version);
            })
            .findFirst()
            .map(path -> {
                try {
                    return new String(Files.readAllBytes(path));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read rollback script: " + path, e);
                }
            })
            .orElse(null);
    }
    
    /**
     * 执行迁移脚本
     * 在事务中执行单个迁移脚本，并记录执行历史
     * 
     * @param script 要执行的迁移脚本
     * @throws SQLException 当脚本执行失败时抛出
     */
    public void executeMigration(MigrationScript script) throws SQLException {
        try {
            connection.setAutoCommit(false);
            long startTime = System.currentTimeMillis();
            
            try (PreparedStatement stmt = connection.prepareStatement(script.getSql())) {
                stmt.execute();
            }
            
            // 记录迁移历史
            recordMigration(script, System.currentTimeMillis() - startTime, true);
            
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            recordMigration(script, 0, false);
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }
    
    /**
     * 记录迁移历史
     * 将迁移脚本的执行结果记录到版本控制表中
     * 
     * @param script 执行的迁移脚本
     * @param executionTime 执行耗时（毫秒）
     * @param success 是否执行成功
     * @throws SQLException 当记录历史失败时抛出
     */
    private void recordMigration(MigrationScript script, long executionTime, boolean success) throws SQLException {
        String sql = String.format(
            "INSERT INTO %s (version_rank, installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) " +
            "VALUES (?, ?, ?, ?, 'SQL', ?, ?, ?, ?, ?)",
            FlyDB.VERSION_TABLE
        );
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int versionRank = Integer.parseInt(script.getVersion());
            stmt.setInt(1, versionRank);
            stmt.setInt(2, versionRank);
            stmt.setString(3, script.getVersion());
            stmt.setString(4, script.getDescription());
            stmt.setString(5, script.getFilename());
            stmt.setInt(6, script.getSql().hashCode());
            stmt.setString(7, System.getProperty("user.name"));
            stmt.setLong(8, executionTime);
            stmt.setBoolean(9, success);
            stmt.executeUpdate();
        }
    }
    
    /**
     * 从文件名中提取版本号
     * 文件名格式必须为：V<版本号>__<描述>.sql
     * 
     * @param filename 迁移脚本文件名
     * @return 版本号，如果文件名格式不正确则返回null
     */
    private String extractVersion(String filename) {
        Matcher matcher = VERSION_PATTERN.matcher(filename);
        return matcher.matches() ? matcher.group(1) : null;
    }
}