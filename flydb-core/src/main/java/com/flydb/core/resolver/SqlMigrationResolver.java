package com.flydb.core.resolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
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
            "^([VU])(\\d[\\d.]*?)__([^/]+)\\.sql$");
    // 可重复：R__<description>.sql
    private static final Pattern REPEATABLE_PATTERN = Pattern.compile(
            "^R__([^/]+)\\.sql$");
    // 旧式阻断：R\d+__<description>.sql
    private static final Pattern LEGACY_R_PATTERN = Pattern.compile(
            "^R\\d+__([^/]+)\\.sql$");

    @Override
    public List<ResolvedMigration> resolveMigrations(ResolverContext context) {
        List<ResolvedMigration> all = new ArrayList<ResolvedMigration>();
        Map<String, String> seenVersions = new HashMap<String, String>();

        for (String location : context.locations()) {
            List<ScriptFile> scripts = scanLocation(location, context);
            for (ScriptFile script : scripts) {
                ResolvedMigration resolved = parseFile(script, context);
                if (resolved == null) {
                    continue;
                }
                // 重复版本检测
                MigrationVersion version = resolved.version();
                if (version != null) {
                    String versionKey = resolved.type().name() + ":" + version;
                    String existing = seenVersions.get(versionKey);
                    if (existing != null) {
                        throw new FlydbException(ErrorCode.DUPLICATE_VERSION,
                                "版本 " + version + " 冲突: " + existing + " 与 " + resolved.script());
                    }
                    seenVersions.put(versionKey, resolved.script());
                }
                all.add(resolved);
            }
        }

        // 排序：版本化升序 → 可重复按描述升序
        Collections.sort(all, (a, b) -> {
            MigrationVersion va = a.version();
            MigrationVersion vb = b.version();
            if (va == null && vb == null) {
                return description(a).compareTo(description(b));
            }
            if (va == null) {
                return 1;
            }
            if (vb == null) {
                return -1;
            }
            int cmp = va.compareTo(vb);
            if (cmp != 0) {
                return cmp;
            }
            return description(a).compareTo(description(b));
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
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
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
            if (relative.indexOf('/') >= 0 || !relative.endsWith(".sql")) continue;
            entries.add(new ScriptFile(relative, readJarEntry(jarFile, entry)));
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
            if (relative.indexOf('/') >= 0 || !relative.endsWith(".sql")) continue;
            entries.add(new ScriptFile(relative, readJarEntry(jarFile, entry)));
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
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "文件系统迁移目录不存在: " + path);
        }
        scanDirectory(dir, entries);
        return entries;
    }

    private static void scanDirectory(File dir, List<ScriptFile> entries) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                continue;
            }
            String name = file.getName();
            if (!name.endsWith(".sql")) {
                continue;
            }
            // 只使用文件名作为 script 字段
            entries.add(new ScriptFile(name, file.toPath()));
        }
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
            return ResolvedMigration.of(null, description, name, checksum, MigrationType.SQL);
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
            return ResolvedMigration.of(version, description, name, checksum,
                    isUndo ? MigrationType.UNDO_SQL : MigrationType.SQL);
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
        final Path path;
        final byte[] content;

        ScriptFile(String filename, Path path) {
            this.filename = filename;
            this.path = path;
            this.content = null;
        }

        ScriptFile(String filename, byte[] content) {
            this.filename = filename;
            this.path = null;
            this.content = content;
        }
    }
}
