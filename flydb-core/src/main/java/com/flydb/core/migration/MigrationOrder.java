package com.flydb.core.migration;

/** SQL 迁移发现后的确定性排序规则。 */
public enum MigrationOrder {
    /** 按文件版本的数字/字母 token 自然顺序排序；默认值。 */
    VERSION,
    /** 按目录版本、文件版本、规范化相对路径排序。 */
    DIRECTORY_VERSION
}
