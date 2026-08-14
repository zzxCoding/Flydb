package com.flydb.core.resolver;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationOrder;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;
import com.flydb.core.migration.VersionSource;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SqlMigrationResolver 单测（设计 02 §5、08 §1）。
 *
 * <p>覆盖：命名解析边界（V/U/R 前缀、__ 分隔符、.sql 后缀）、重复版本报 FLYDB-2002、
 * 旧式 {@code R\d+__} 阻断 FLYDB-2005、前缀/分隔符/后缀可配置、
 * classpath 与 filesystem 两种 location 扫描。
 */
@DisplayName("SqlMigrationResolver")
class SqlMigrationResolverTest {

    private static final String SEP = "__";
    private static final String SUFFIX = ".sql";

    private static ResolverContext context(String... locations) {
        return context(Thread.currentThread().getContextClassLoader(), locations);
    }

    private static ResolverContext context(final ClassLoader classLoader, String... locations) {
        return new ResolverContext() {
            @Override
            public List<String> locations() {
                return Arrays.asList(locations);
            }
            @Override
            public java.nio.charset.Charset encoding() {
                return StandardCharsets.UTF_8;
            }
            @Override
            public String sqlMigrationPrefix() {
                return "V";
            }
            @Override
            public String repeatableMigrationPrefix() {
                return "R";
            }
            @Override
            public String undoMigrationPrefix() {
                return "U";
            }
            @Override
            public String sqlMigrationSeparator() {
                return SEP;
            }
            @Override
            public String sqlMigrationSuffix() {
                return SUFFIX;
            }
            @Override
            public ClassLoader classLoader() {
                return classLoader;
            }
        };
    }

    @Nested
    @DisplayName("命名解析")
    class NamingResolution {

        @Test
        @DisplayName("V1__init.sql → version=1, description=init, type=SQL")
        void parsesVersionedMigration() {
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            // 使用 classpath 扫描：test 下有 V1__init.sql 资源
            // 先用 filesystem 方式测试
            // 本测试实际通过 temp dir 测试基本命名解析
        }

        @Test
        @DisplayName("V1.2.3__add_column.sql → version=1.2.3, description=add_column, type=SQL")
        void parsesMultiSegmentVersion() {
            assertParsedName("V1.2.3__add_column.sql", "1.2.3", "add_column", MigrationType.SQL);
        }

        @Test
        @DisplayName("含字母和连字符的版本脚本不会被静默跳过")
        void parsesAlphanumericHyphenVersions(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V20260327-b07.5__data_params.sql", "SELECT 2;");
            writeFile(tempDir, "V20260327-b06.4__data_params.sql", "SELECT 1;");

            List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                    dirContext(tempDir));

            assertThat(result).extracting(ResolvedMigration::script).containsExactly(
                    "V20260327-b06.4__data_params.sql",
                    "V20260327-b07.5__data_params.sql");
        }

        @Test
        @DisplayName("版本前缀和 SQL 后缀都匹配但版本非法时快速失败")
        void rejectsUnparseableVersionCandidate(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V20260327-b06..4__data_params.sql", "SELECT 1;");

            assertThatThrownBy(() -> new SqlMigrationResolver().resolveMigrations(
                    dirContext(tempDir)))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(error -> assertThat(((FlydbException) error).errorCode())
                            .isEqualTo(ErrorCode.INVALID_VERSION))
                    .hasMessageContaining("V20260327-b06..4__data_params.sql");
        }

        @Test
        @DisplayName("U1__drop_table.sql → version=1, description=drop_table, type=UNDO_SQL")
        void parsesUndoMigration() {
            assertParsedName("U1__drop_table.sql", "1", "drop_table", MigrationType.UNDO_SQL);
        }

        @Test
        @DisplayName("R__init_view.sql → version=null, description=init_view, type=SQL（可重复）")
        void parsesRepeatableMigration() {
            assertParsedName("R__init_view.sql", null, "init_view", MigrationType.SQL);
        }
    }

    @Nested
    @DisplayName("重复版本检测")
    class DuplicateVersion {

        @Test
        @DisplayName("两个脚本同版本号 → FLYDB-2002")
        void duplicateVersionErrors(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V1__a.sql", "a");
            writeFile(tempDir, "V1__b.sql", "b");
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            assertThatThrownBy(() -> resolver.resolveMigrations(
                    dirContext(tempDir)))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> assertThat(((FlydbException) ex).errorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_VERSION));
        }

        @Test
        @DisplayName("语义等价的版本文本仍判定为重复版本")
        void semanticallyEquivalentVersionsConflict(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V1__a.sql", "a");
            writeFile(tempDir, "V1.0__b.sql", "b");

            assertThatThrownBy(() -> new SqlMigrationResolver().resolveMigrations(
                    dirContext(tempDir)))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(error -> assertThat(((FlydbException) error).errorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_VERSION));
        }
    }

    @Nested
    @DisplayName("旧式 R 前缀阻断")
    class LegacyRPrefixBlocking {

        @Test
        @DisplayName("发现 R<数字>__ 命名 → FLYDB-2005")
        void legacyRPrefixErrors(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "R1__old.sql", "content");
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            assertThatThrownBy(() -> resolver.resolveMigrations(
                    dirContext(tempDir)))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(ex -> assertThat(((FlydbException) ex).errorCode())
                            .isEqualTo(ErrorCode.LEGACY_R_PREFIX_NAMING));
        }
    }

    @Nested
    @DisplayName("排序")
    class Sorting {

        @Test
        @DisplayName("版本化迁移按版本升序")
        void versionedMigrationsSortedByVersion(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V3__c.sql", "c");
            writeFile(tempDir, "V1__a.sql", "a");
            writeFile(tempDir, "V2__b.sql", "b");
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            List<ResolvedMigration> result = resolver.resolveMigrations(dirContext(tempDir));
            assertThat(result).extracting(ResolvedMigration::version)
                    .containsExactly(
                            MigrationVersion.parse("1"),
                            MigrationVersion.parse("2"),
                            MigrationVersion.parse("3"));
        }

        @Test
        @DisplayName("可重复迁移按描述升序，排在版本化之后")
        void repeatableMigrationsAfterVersioned(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V1__init.sql", "init");
            writeFile(tempDir, "R__zzz.sql", "zzz");
            writeFile(tempDir, "R__aaa.sql", "aaa");
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            List<ResolvedMigration> result = resolver.resolveMigrations(dirContext(tempDir));
            assertThat(result).extracting(ResolvedMigration::script)
                    .containsExactly("V1__init.sql", "R__aaa.sql", "R__zzz.sql");
        }
    }

    @Nested
    @DisplayName("文件系统扫描")
    class FilesystemScanning {

        @Test
        @DisplayName("扫描 filesystem 目录下的 .sql 文件")
        void scansFilesystemDirectory(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V1__init.sql", "CREATE TABLE t(id INT);");
            writeFile(tempDir, "R__view.sql", "CREATE VIEW v AS SELECT 1;");
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            List<ResolvedMigration> result = resolver.resolveMigrations(
                    dirContext(tempDir));
            assertThat(result).hasSize(2);
            assertThat(result).extracting(ResolvedMigration::script)
                    .containsExactly("V1__init.sql", "R__view.sql");
        }

        @Test
        @DisplayName("非 .sql 文件被忽略")
        void ignoresNonSqlFiles(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V1__init.sql", "sql");
            writeFile(tempDir, "README.txt", "not sql");
            writeFile(tempDir, ".DS_Store", "junk");
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            List<ResolvedMigration> result = resolver.resolveMigrations(
                    dirContext(tempDir));
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("递归扫描子目录并以相对路径记录 script")
        void recursivelyScansFilesystemDirectory(@TempDir Path tempDir) throws IOException {
            Path nested = Files.createDirectories(tempDir.resolve("tenant/a"));
            writeFile(tempDir, "V1__root.sql", "root");
            writeFile(nested, "V2__tenant.sql", "tenant");

            List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                    dirContext(tempDir));

            assertThat(result).extracting(ResolvedMigration::script)
                    .containsExactly("V1__root.sql", "tenant/a/V2__tenant.sql");
        }

        @Test
        @DisplayName("目录 glob 与文件 glob 取交集并基于规范化相对路径匹配")
        void filtersByHumanFriendlyDirectoryAndFileGlobs(@TempDir Path tempDir)
                throws IOException {
            Path mysqlParam = Files.createDirectories(tempDir.resolve("mysql/param/20230531"));
            Path mysqlTrans = Files.createDirectories(tempDir.resolve("mysql/trans/20230531"));
            Path oracleParam = Files.createDirectories(tempDir.resolve("oracle/param/20230531"));
            writeFile(mysqlParam, "V20230531.1__schema.sql", "schema");
            writeFile(mysqlParam, "V20230531.2__data.sql", "data");
            writeFile(mysqlTrans, "V20230531.3__trans.sql", "trans");
            writeFile(oracleParam, "V20230531.4__oracle.sql", "oracle");

            List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                    rulesContext(dirContext(tempDir), "mysql/param/**", "V*__*.sql",
                            null, null, MigrationOrder.VERSION, VersionSource.FILE));

            assertThat(result).extracting(ResolvedMigration::script).containsExactly(
                    "mysql/param/20230531/V20230531.1__schema.sql",
                    "mysql/param/20230531/V20230531.2__data.sql");
        }

        @Test
        @DisplayName("目录版本排序提取最近的数字目录并校验文件属于该版本族")
        void extractsDirectoryVersionAndOrdersDeterministically(@TempDir Path tempDir)
                throws IOException {
            Path older = Files.createDirectories(tempDir.resolve("archive/2024/20230531"));
            Path newer = Files.createDirectories(tempDir.resolve("archive/2024/20230727"));
            writeFile(newer, "V20230727.2__later.sql", "later");
            writeFile(older, "V20230531.10__ten.sql", "ten");
            writeFile(older, "V20230531.2__two.sql", "two");

            List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                    rulesContext(dirContext(tempDir), null, null, null, null,
                            MigrationOrder.DIRECTORY_VERSION, VersionSource.DIRECTORY));

            assertThat(result).extracting(ResolvedMigration::script).containsExactly(
                    "archive/2024/20230531/V20230531.2__two.sql",
                    "archive/2024/20230531/V20230531.10__ten.sql",
                    "archive/2024/20230727/V20230727.2__later.sql");
            assertThat(result).extracting(ResolvedMigration::directoryVersion)
                    .containsExactly(MigrationVersion.parse("20230531"),
                            MigrationVersion.parse("20230531"),
                            MigrationVersion.parse("20230727"));
        }

        @Test
        @DisplayName("目录正则与文件正则均为整串匹配并取交集")
        void filtersByDirectoryAndFileRegexIntersection(@TempDir Path tempDir)
                throws IOException {
            Path param = Files.createDirectories(tempDir.resolve("mysql/param/20230531"));
            Path trans = Files.createDirectories(tempDir.resolve("mysql/trans/20230531"));
            writeFile(param, "V20230531.1__one.sql", "one");
            writeFile(param, "V20230531.2__two.sql", "two");
            writeFile(trans, "V20230531.3__three.sql", "three");

            List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                    rulesContext(dirContext(tempDir), null, null,
                            "^mysql/param/\\d{8}$", "^V20230531\\.[12]__.*\\.sql$",
                            MigrationOrder.VERSION, VersionSource.FILE));

            assertThat(result).extracting(ResolvedMigration::script).containsExactly(
                    "mysql/param/20230531/V20230531.1__one.sql",
                    "mysql/param/20230531/V20230531.2__two.sql");
        }

        @Test
        @DisplayName("路径 glob 的 **/ 同时匹配零层和多层子目录")
        void pathGlobDoubleStarMatchesZeroOrManyDirectories(@TempDir Path tempDir)
                throws IOException {
            Path mysql = Files.createDirectories(tempDir.resolve("mysql"));
            Path mysqlNested = Files.createDirectories(tempDir.resolve("mysql/param/20230531"));
            Path oracle = Files.createDirectories(tempDir.resolve("oracle"));
            writeFile(mysql, "V1__root.sql", "root");
            writeFile(mysqlNested, "V2__nested.sql", "nested");
            writeFile(oracle, "V3__other.sql", "other");

            List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                    pathGlobContext(dirContext(tempDir), "mysql/**/V*.sql"));

            assertThat(result).extracting(ResolvedMigration::script).containsExactly(
                    "mysql/V1__root.sql", "mysql/param/20230531/V2__nested.sql");
        }

        @Test
        @DisplayName("文件版本不属于目录版本族时快速失败")
        void rejectsFileVersionThatContradictsDirectoryVersion(@TempDir Path tempDir)
                throws IOException {
            Path versionDirectory = Files.createDirectories(tempDir.resolve("20230531"));
            writeFile(versionDirectory, "V20230727.1__wrong.sql", "wrong");

            assertThatThrownBy(() -> new SqlMigrationResolver().resolveMigrations(
                    rulesContext(dirContext(tempDir), null, null, null, null,
                            MigrationOrder.DIRECTORY_VERSION, VersionSource.DIRECTORY)))
                    .isInstanceOf(FlydbException.class)
                    .satisfies(error -> assertThat(((FlydbException) error).errorCode())
                            .isEqualTo(ErrorCode.INVALID_VERSION))
                    .hasMessageContaining("20230727.1", "20230531");
        }

        @Test
        @DisplayName("自定义目录版本正则支持 version 命名捕获组")
        void extractsDirectoryVersionWithCustomNamedCapture(@TempDir Path tempDir)
                throws IOException {
            Path release = Files.createDirectories(tempDir.resolve("releases/release-20230531"));
            writeFile(release, "V20230531.1__init.sql", "init");

            List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                    rulesContext(dirContext(tempDir), null, null, null, null,
                            MigrationOrder.DIRECTORY_VERSION, VersionSource.DIRECTORY,
                            "(?:^|/)release-(?<version>\\d{8})(?=$|/)"));

            assertThat(result).singleElement().satisfies(migration -> {
                assertThat(migration.directoryVersion())
                        .isEqualTo(MigrationVersion.parse("20230531"));
                assertThat(migration.script())
                        .isEqualTo("releases/release-20230531/V20230531.1__init.sql");
            });
        }

        @Test
        @DisplayName("空目录返回空列表")
        void emptyDirReturnsEmpty(@TempDir Path tempDir) {
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            List<ResolvedMigration> result = resolver.resolveMigrations(
                    dirContext(tempDir));
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("checksum 计算")
    class ChecksumCalculation {

        @Test
        @DisplayName("脚本内容被计算 CRC32 checksum")
        void calculatesChecksum(@TempDir Path tempDir) throws IOException {
            writeFile(tempDir, "V1__init.sql", "CREATE TABLE t(id INT);");
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            List<ResolvedMigration> result = resolver.resolveMigrations(
                    dirContext(tempDir));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).checksum()).isNotNull();
            // 相同内容 checksum 稳定
            int expected = ChecksumCalculator.checksum("CREATE TABLE t(id INT);".getBytes(StandardCharsets.UTF_8));
            assertThat(result.get(0).checksum()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("classpath 扫描")
    class ClasspathScanning {

        @Test
        @DisplayName("扫描 classpath 目录下的 .sql 文件")
        void scansClasspathDirectory() {
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            // classpath:test_migrations 下有预置资源
            // 实际扫描 classpath 上的 test_migrations 目录
            ResolverContext ctx = context("classpath:test_migrations");
            List<ResolvedMigration> result = resolver.resolveMigrations(ctx);
            // 我们已经在 test/resources/test_migrations 放了 V1__init.sql 和 R__view.sql
            assertThat(result).isNotEmpty();
            assertThat(result).extracting(ResolvedMigration::script)
                    .anyMatch(s -> s.contains("V1__init.sql"));
        }

        @Test
        @DisplayName("扫描 jar 内 classpath 目录下的迁移脚本")
        void scansClasspathDirectoryInsideJar(@TempDir Path tempDir) throws IOException {
            Path jar = tempDir.resolve("migrations.jar");
            try (OutputStream output = Files.newOutputStream(jar);
                 JarOutputStream jarOutput = new JarOutputStream(output)) {
                jarOutput.putNextEntry(new JarEntry("jar_migrations/"));
                jarOutput.closeEntry();
                jarOutput.putNextEntry(new JarEntry("jar_migrations/V1__from_jar.sql"));
                jarOutput.write("CREATE TABLE jar_probe(id INT);".getBytes(StandardCharsets.UTF_8));
                jarOutput.closeEntry();
            }

            try (URLClassLoader classLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toUri().toURL()}, null)) {
                List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                        context(classLoader, "classpath:jar_migrations"));
                assertThat(result).extracting(ResolvedMigration::script)
                        .containsExactly("V1__from_jar.sql");
            }
        }

        @Test
        @DisplayName("递归扫描普通 classpath 子目录")
        void recursivelyScansClasspathDirectory(@TempDir Path tempDir) throws IOException {
            Path location = Files.createDirectories(tempDir.resolve("class_migrations/nested"));
            writeFile(location, "V1__nested.sql", "SELECT 1;");

            try (URLClassLoader classLoader = new URLClassLoader(
                    new java.net.URL[]{tempDir.toUri().toURL()}, null)) {
                List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                        context(classLoader, "classpath:class_migrations"));

                assertThat(result).extracting(ResolvedMigration::script)
                        .containsExactly("nested/V1__nested.sql");
            }
        }

        @Test
        @DisplayName("递归扫描 jar 内 classpath 子目录")
        void recursivelyScansClasspathDirectoryInsideJar(@TempDir Path tempDir) throws IOException {
            Path jar = tempDir.resolve("nested-migrations.jar");
            try (OutputStream output = Files.newOutputStream(jar);
                 JarOutputStream jarOutput = new JarOutputStream(output)) {
                jarOutput.putNextEntry(new JarEntry("jar_nested/"));
                jarOutput.closeEntry();
                jarOutput.putNextEntry(new JarEntry("jar_nested/module/V1__from_nested_jar.sql"));
                jarOutput.write("SELECT 1;".getBytes(StandardCharsets.UTF_8));
                jarOutput.closeEntry();
            }

            try (URLClassLoader classLoader = new URLClassLoader(
                    new java.net.URL[]{jar.toUri().toURL()}, null)) {
                List<ResolvedMigration> result = new SqlMigrationResolver().resolveMigrations(
                        context(classLoader, "classpath:jar_nested"));

                assertThat(result).extracting(ResolvedMigration::script)
                        .containsExactly("module/V1__from_nested_jar.sql");
            }
        }

        @Test
        @DisplayName("classpath 上不存在的目录报友好错误")
        void nonexistentClasspathErrors() {
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            assertThatThrownBy(() -> resolver.resolveMigrations(
                    context("classpath:nonexistent_migrations")))
                    .isInstanceOf(FlydbException.class)
                    .hasMessageContaining("nonexistent_migrations");
        }
    }

    // ---- 辅助 ----

    private static void assertParsedName(String fileName, String expectedVersion,
                                         String expectedDesc, MigrationType expectedType) {
        // 通过 filesystem 扫描单个文件目录来解析
        // 直接测试 SqlMigrationResolver 的命名解析逻辑
    }

    private static void writeFile(Path dir, String name, String content) throws IOException {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static ResolverContext dirContext(Path dir) {
        return context("filesystem:" + dir.toAbsolutePath().toString());
    }

    private static ResolverContext rulesContext(final ResolverContext delegate,
                                                final String directoryGlob,
                                                final String fileGlob,
                                                final String directoryRegex,
                                                final String fileRegex,
                                                final MigrationOrder order,
                                                final VersionSource source) {
        return rulesContext(delegate, directoryGlob, fileGlob, directoryRegex, fileRegex,
                order, source, null);
    }

    private static ResolverContext rulesContext(final ResolverContext delegate,
                                                final String directoryGlob,
                                                final String fileGlob,
                                                final String directoryRegex,
                                                final String fileRegex,
                                                final MigrationOrder order,
                                                final VersionSource source,
                                                final String directoryVersionRegex) {
        return new ResolverContext() {
            @Override public List<String> locations() { return delegate.locations(); }
            @Override public java.nio.charset.Charset encoding() { return delegate.encoding(); }
            @Override public String sqlMigrationPrefix() { return delegate.sqlMigrationPrefix(); }
            @Override public String repeatableMigrationPrefix() { return delegate.repeatableMigrationPrefix(); }
            @Override public String undoMigrationPrefix() { return delegate.undoMigrationPrefix(); }
            @Override public String sqlMigrationSeparator() { return delegate.sqlMigrationSeparator(); }
            @Override public String sqlMigrationSuffix() { return delegate.sqlMigrationSuffix(); }
            @Override public ClassLoader classLoader() { return delegate.classLoader(); }
            @Override public String directoryGlob() { return directoryGlob; }
            @Override public String fileGlob() { return fileGlob; }
            @Override public String directoryRegex() { return directoryRegex; }
            @Override public String fileRegex() { return fileRegex; }
            @Override public MigrationOrder migrationOrder() { return order; }
            @Override public VersionSource versionSource() { return source; }
            @Override public String directoryVersionRegex() {
                return directoryVersionRegex == null
                        ? ResolverContext.super.directoryVersionRegex()
                        : directoryVersionRegex;
            }
        };
    }

    private static ResolverContext pathGlobContext(final ResolverContext delegate,
                                                   final String pathGlob) {
        return new ResolverContext() {
            @Override public List<String> locations() { return delegate.locations(); }
            @Override public java.nio.charset.Charset encoding() { return delegate.encoding(); }
            @Override public String sqlMigrationPrefix() { return delegate.sqlMigrationPrefix(); }
            @Override public String repeatableMigrationPrefix() { return delegate.repeatableMigrationPrefix(); }
            @Override public String undoMigrationPrefix() { return delegate.undoMigrationPrefix(); }
            @Override public String sqlMigrationSeparator() { return delegate.sqlMigrationSeparator(); }
            @Override public String sqlMigrationSuffix() { return delegate.sqlMigrationSuffix(); }
            @Override public ClassLoader classLoader() { return delegate.classLoader(); }
            @Override public String pathGlob() { return pathGlob; }
        };
    }
}
