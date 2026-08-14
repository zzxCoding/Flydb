package com.flydb.core.resolver;

import java.nio.charset.Charset;
import java.util.List;

import com.flydb.core.migration.MigrationOrder;
import com.flydb.core.migration.VersionSource;

/**
 * 解析器上下文（设计 02 §5）。
 *
 * <p>提供迁移解析器所需的全部配置信息。由命令层在运行期构造，注入给所有已注册的 {@link MigrationResolver}。
 */
public interface ResolverContext {

    /** 扫描路径列表：支持 {@code classpath:} 与 {@code filesystem:} 前缀。 */
    List<String> locations();

    /** 脚本文件编码。 */
    Charset encoding();

    /** 版本化迁移前缀，默认 "V"。 */
    String sqlMigrationPrefix();

    /** 可重复迁移前缀，默认 "R"。 */
    String repeatableMigrationPrefix();

    /** 撤销迁移前缀，默认 "U"。 */
    String undoMigrationPrefix();

    /** 版本与描述之间的分隔符，默认 "__"。 */
    String sqlMigrationSeparator();

    /** 脚本文件后缀，默认 ".sql"。 */
    String sqlMigrationSuffix();

    /** 类加载器（用于 classpath 扫描）。 */
    ClassLoader classLoader();

    /** 以下高级发现规则均为可选；默认实现保持第三方 ResolverContext 二进制/源码兼容。 */
    default String directoryGlob() { return null; }
    default String fileGlob() { return null; }
    default String pathGlob() { return null; }
    default String directoryRegex() { return null; }
    default String fileRegex() { return null; }
    default String pathRegex() { return null; }
    default MigrationOrder migrationOrder() { return MigrationOrder.VERSION; }
    default VersionSource versionSource() { return VersionSource.FILE; }
    default String directoryVersionRegex() {
        return "(?:^|/)(?<version>\\d+(?:\\.\\d+)*)(?=$|/)";
    }
}
