package com.flydb.core.migration;

/** 版本筛选所读取的版本来源。 */
public enum VersionSource {
    /** 从迁移文件名读取的脚本版本；默认值。 */
    FILE,
    /** 从迁移脚本相对目录提取的目录版本。 */
    DIRECTORY
}
