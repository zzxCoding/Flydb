package com.flydb.core.resolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.MigrationType;
import com.flydb.core.migration.MigrationOrder;
import com.flydb.core.migration.MigrationVersion;
import com.flydb.core.migration.ResolvedMigration;

/**
 * SQL 迁移脚本解析器（设计 02 §5）。
 *
 * <p>扫描 {@code classpath:} 与 {@code filesystem:} 两种 location 下的 {@code .sql} 文件，
 * 按命名规范解析出版本/描述/类型，读取内容计算 CRC32 checksum。
 *
 * <p>强制排序：输出前按 {@link MigrationVersion} 升序（版本化）与 description 升序（可重复）排序。
 * 重复版本 → {@link ErrorCode#DUPLICATE_VERSION}（FLYDB-2002）。
 * 旧式 {@code R\d+__} 命名 → {@link ErrorCode#LEGACY_R_PREFIX_NAMING}（FLYDB-2005）。
 */
public final class SqlMigrationResolver implements MigrationResolver {

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILESYSTEM_PREFIX = "filesystem:";

    // 文件名模式：V<version>__<description>.sql 或 U<version>__<description>.sql 或 R__<description>.sql
    private static final Pattern VERSIONED_PATTERN = Pattern.compile(
            "^([VU])(\\d[A-Za-z0-9]*(?:[._-][A-Za-z0-9]+)*)__([^/]+)\\.sql$");
    // 可重复：R__<description>.sql
    private static final Pattern REPEATABLE_PATTERN = Pattern.compile(
            "^R__([^/]+)\\.sql$");
    // 旧式阻断：R\d+__<description>.sql
    private static final Pattern LEGACY_R_PATTERN = Pattern.compile(
            "^R\\d+__([^/]+)\\.sql$");

    @Override
    public List<ResolvedMigration> resolveMigrations(ResolverContext context) {
        List<ResolvedMigration> all = new ArrayList<ResolvedMigration>();
        Map<MigrationVersion, String> seenVersions =
                new HashMap<MigrationVersion, String>();
        Map<MigrationVersion, String> seenUndoVersions =
                new HashMap<MigrationVersion, String>();
        MigrationPathFilter pathFilter = MigrationPathFilter.from(context);
        boolean needsDirectoryVersion = context.versionSource()
                == com.flydb.core.migration.VersionSource.DIRECTORY
                || context.migrationOrder() == MigrationOrder.DIRECTORY_VERSION;
        DirectoryVersionExtractor directoryVersions = needsDirectoryVersion
                ? DirectoryVersionExtractor.compile(context.directoryVersionRegex()) : null;

        for (String location : context.locations()) {
            List<ScriptFile> scripts = scanLocation(location, context);
            for (ScriptFile script : scripts) {
                if (!pathFilter.matches(script.script)) {
                    continue;
                }
                ResolvedMigration resolved = parseFile(script, context);
                if (resolved == null) {
                    continue;
                }
                if (resolved.version() != null && directoryVersions != null) {
                    MigrationVersion directoryVersion = directoryVersions.extract(resolved.script());
                    if (!resolved.version().isSameOrDescendantOf(directoryVersion)) {
                        throw new FlydbException(ErrorCode.INVALID_VERSION,
                                "文件版本 " + resolved.version() + " 不属于目录版本 "
                                        + directoryVersion + ": " + resolved.script());
                    }
                    resolved = ResolvedMigration.of(resolved.version(), directoryVersion,
                            resolved.description(), resolved.script(), resolved.checksum(),
                            resolved.type());
                }
                // 重复版本检测
                MigrationVersion version = resolved.version();
                if (version != null) {
                    Map<MigrationVersion, String> seen = resolved.type() == MigrationType.UNDO_SQL
                            ? seenUndoVersions : seenVersions;
                    String existing = seen.get(version);
                    if (existing != null) {
                        throw new FlydbException(ErrorCode.DUPLICATE_VERSION,
                                "版本 " + version + " 冲突: " + existing + " 与 " + resolved.script());
                    }
                    seen.put(version, resolved.script());
                }
                all.add(resolved);
            }
        }

        // 排序：版本化升序 → 可重复按描述升序
        final MigrationOrder migrationOrder = context.migrationOrder();
        Collections.sort(all, (a, b) -> {
            MigrationVersion va = a.version();
            MigrationVersion vb = b.version();
            if (va == null && vb == null) {
                int byDescription = description(a).compareTo(description(b));
                return byDescription != 0 ? byDescription : a.script().compareTo(b.script());
            }
            if (va == null) {
                return 1;
            }
            if (vb == null) {
                return -1;
            }
            int cmp = 0;
            if (migrationOrder == MigrationOrder.DIRECTORY_VERSION) {
                cmp = a.directoryVersion().compareTo(b.directoryVersion());
            }
            if (cmp == 0) {
                cmp = va.compareTo(vb);
            }
            if (cmp != 0) {
                return cmp;
            }
            return a.script().compareTo(b.script());
        });

        return Collections.unmodifiableList(all);
    }

    private static String description(ResolvedMigration m) {
        String d = m.description();
        return d == null ? "" : d;
    }

    /** 扫描单个 location 下的所有 .sql 文件。 */
    private static List<ScriptFile> scanLocation(String location, ResolverContext context) {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return scanClasspath(location.substring(CLASSPATH_PREFIX.length()), context);
        } else if (location.startsWith(FILESYSTEM_PREFIX)) {
            return scanFilesystem(location.substring(FILESYSTEM_PREFIX.length()), context);
        } else {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "不支持的 location 前缀: " + location + "（应使用 classpath: 或 filesystem:）");
        }
    }

    private static List<ScriptFile> scanClasspath(String path, ResolverContext context) {
        List<ScriptFile> entries = new ArrayList<ScriptFile>();
        try {
            String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
            Enumeration<URL> resources = context.classLoader().getResources(normalizedPath);
            boolean found = false;
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                found = true;
                if ("file".equals(url.getProtocol())) {
                    File dir = new File(url.toURI());
                    if (dir.isDirectory()) {
                        scanDirectory(dir, entries);
                    }
                } else {
                    scanJar(url, normalizedPath, entries);
                }
            }
            if (!found) {
                throw new FlydbException(ErrorCode.LOCATION_NOT_FOUND,
                        "classpath 迁移目录不存在: " + path);
            }
        } catch (URISyntaxException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "classpath 路径解析失败: " + path, e);
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "扫描 classpath 失败: " + path, e);
        }
        return entries;
    }

    private static void scanJar(URL resource, String normalizedPath,
                                List<ScriptFile> entries) throws IOException {
        java.net.URLConnection connection = resource.openConnection();
        if (!(connection instanceof JarURLConnection)) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "不支持的 classpath 资源协议: " + resource.getProtocol()
                            + "（" + resource + "）");
        }

        JarURLConnection jarConnection = (JarURLConnection) connection;
        JarFile jarFile = jarConnection.getJarFile();
        String entryPrefix = jarConnection.getEntryName();
        if (entryPrefix == null || entryPrefix.isEmpty()) {
            entryPrefix = normalizedPath;
        }
        entryPrefix = withoutTrailingSlash(entryPrefix) + "/";

        int before = entries.size();
        scanJarEntries(jarFile, entryPrefix, entries);
        if (entries.size() == before && !entryPrefix.equals(normalizedPath + "/")) {
            scanJarEntries(jarFile, withoutTrailingSlash(normalizedPath) + "/", entries);
        }
        if (entries.size() == before) {
            scanJarEntriesBySuffix(jarFile,
                    "/" + withoutTrailingSlash(normalizedPath) + "/", entries);
        }
    }

    private static void scanJarEntries(JarFile jarFile, String prefix,
                                       List<ScriptFile> entries) throws IOException {
        Enumeration<JarEntry> jarEntries = jarFile.entries();
        while (jarEntries.hasMoreElements()) {
            JarEntry entry = jarEntries.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
            String relative = entry.getName().substring(prefix.length());
            if (!relative.endsWith(".sql")) continue;
            entries.add(new ScriptFile(fileName(relative), relative,
                    readJarEntry(jarFile, entry)));
        }
    }

    private static void scanJarEntriesBySuffix(JarFile jarFile, String suffixPrefix,
                                               List<ScriptFile> entries) throws IOException {
        Enumeration<JarEntry> jarEntries = jarFile.entries();
        while (jarEntries.hasMoreElements()) {
            JarEntry entry = jarEntries.nextElement();
            if (entry.isDirectory()) continue;
            int location = entry.getName().lastIndexOf(suffixPrefix);
            if (location < 0) continue;
            String relative = entry.getName().substring(location + suffixPrefix.length());
            if (!relative.endsWith(".sql")) continue;
            entries.add(new ScriptFile(fileName(relative), relative,
                    readJarEntry(jarFile, entry)));
        }
    }

    private static byte[] readJarEntry(JarFile jarFile, JarEntry entry) throws IOException {
        try (InputStream input = jarFile.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String withoutTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static List<ScriptFile> scanFilesystem(String path, ResolverContext context) {
        List<ScriptFile> entries = new ArrayList<ScriptFile>();
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new FlydbException(ErrorCode.LOCATION_NOT_FOUND,
                    "文件系统迁移目录不存在: " + path
                            + "（相对路径按进程当前工作目录解析）");
        }
        try {
            scanDirectory(dir, entries);
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "扫描文件系统迁移目录失败: " + path, e);
        }
        return entries;
    }

    private static void scanDirectory(File dir, final List<ScriptFile> entries)
            throws IOException {
        final Path root = dir.toPath();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String name = file.getFileName().toString();
                if (name.endsWith(".sql")) {
                    String relative = root.relativize(file).toString()
                            .replace(File.separatorChar, '/');
                    entries.add(new ScriptFile(name, relative, file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String fileName(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    /** 解析单个文件为 ResolvedMigration。 */
    private static ResolvedMigration parseFile(ScriptFile entry, ResolverContext context) {
        String name = entry.filename;

        // 旧式 R\d+__ 阻断——必须在可重复解析之前
        Matcher legacyMatcher = LEGACY_R_PATTERN.matcher(name);
        if (legacyMatcher.matches()) {
            throw new FlydbException(ErrorCode.LEGACY_R_PREFIX_NAMING,
                    "文件 " + name + " 使用了旧式 R<数字>__ 命名。"
                            + "R 前缀表示可重复迁移，不带版本号。"
                            + "请更名为 R__" + legacyMatcher.group(1) + ".sql。");
        }

        // 可重复迁移 R__description.sql
        Matcher repeatableMatcher = REPEATABLE_PATTERN.matcher(name);
        if (repeatableMatcher.matches()) {
            String description = repeatableMatcher.group(1);
            byte[] content = readContent(entry);
            int checksum = ChecksumCalculator.checksum(content);
            return ResolvedMigration.of(null, description, entry.script, checksum, MigrationType.SQL);
        }

        // 版本化迁移 V/U<version>__description.sql
        Matcher versionedMatcher = VERSIONED_PATTERN.matcher(name);
        if (versionedMatcher.matches()) {
            String prefix = versionedMatcher.group(1);
            String versionStr = versionedMatcher.group(2);
            if (versionStr.startsWith(".") || versionStr.endsWith(".")) {
                throw new FlydbException(ErrorCode.INVALID_VERSION,
                        "文件 " + name + " 版本号格式错误: " + versionStr);
            }
            MigrationVersion version = MigrationVersion.parse(versionStr);
            String description = versionedMatcher.group(3);
            boolean isUndo = "U".equals(prefix);
            byte[] content = readContent(entry);
            int checksum = ChecksumCalculator.checksum(content);
            return ResolvedMigration.of(version, description, entry.script, checksum,
                    isUndo ? MigrationType.UNDO_SQL : MigrationType.SQL);
        }

        if ((name.startsWith("V") || name.startsWith("U")) && name.endsWith(".sql")) {
            throw new FlydbException(ErrorCode.INVALID_VERSION,
                    "迁移文件名无法解析，拒绝静默跳过: " + entry.script);
        }

        return null;
    }

    private static byte[] readContent(ScriptFile entry) {
        if (entry.content != null) {
            return entry.content;
        }
        try {
            return Files.readAllBytes(entry.path);
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "读取迁移文件失败: " + entry.path, e);
        }
    }

    /** 扫描出的文件条目。 */
    private static final class ScriptFile {
        final String filename;
        final String script;
        final Path path;
        final byte[] content;

        ScriptFile(String filename, String script, Path path) {
            this.filename = filename;
            this.script = script;
            this.path = path;
            this.content = null;
        }

        ScriptFile(String filename, String script, byte[] content) {
            this.filename = filename;
            this.script = script;
            this.path = null;
            this.content = content;
        }
    }
}
