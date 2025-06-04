package com.flydb.core;

/**
 * 数据库迁移脚本类
 * 用于封装数据库迁移脚本的相关信息，包括版本号、文件名和SQL内容
 * 
 * 文件命名规范：
 * - 迁移脚本：V<版本号>__<描述>.sql
 * - 回退脚本：R<版本号>__<描述>.sql
 * 
 * 示例：
 * - V1__create_users_table.sql
 * - R1__drop_users_table.sql
 * 
 * @author FlyDB Team
 */
public class MigrationScript {
    private final String version;
    private final String filename;
    private final String sql;
    
    public MigrationScript(String version, String filename, String sql) {
        this.version = version;
        this.filename = filename;
        this.sql = sql;
    }
    
    public String getVersion() { return version; }
    public String getFilename() { return filename; }
    public String getSql() { return sql; }
    public String getDescription() {
        return filename.substring(filename.indexOf("__") + 2, filename.lastIndexOf("."));
    }
}