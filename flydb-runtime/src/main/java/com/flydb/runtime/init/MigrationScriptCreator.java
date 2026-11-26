package com.flydb.runtime.init;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.migration.*;
import com.flydb.core.resolver.SqlMigrationResolver;

/** 离线创建版本化迁移文件，CLI 与本机工作台共用同一套发现和命名规则。 */
public final class MigrationScriptCreator {
    private MigrationScriptCreator() {}

    public static final class CreatedScript {
        private final Path path;
        private final String version;

        private CreatedScript(Path path, String version) {
            this.path = path;
            this.version = version;
        }

        public Path path() { return path; }
        public String version() { return version; }
    }

    // JVM 内先串行进入，避免文件锁重叠；目录中的文件锁同时协调独立的 CLI 进程。
    public static synchronized CreatedScript create(FlydbConfiguration config, Path workingDirectory,
            String description, String requestedVersion, Path directory) throws IOException {
        if (!description.matches("[\\p{L}\\p{N}][\\p{L}\\p{N} _.-]*") || description.endsWith("."))
            throw invalid("描述须以字母或数字开头，可包含空格、点、下划线和短横线");
        List<Path> roots = new ArrayList<Path>();
        for (String location : config.locations()) {
            if (location.startsWith("filesystem:")) {
                Path rootPath = Paths.get(location.substring(11));
                if (!Files.isDirectory(rootPath)) throw new FlydbException(ErrorCode.LOCATION_NOT_FOUND, "迁移目录不存在: " + rootPath);
                roots.add(rootPath.toRealPath());
            }
        }
        if (roots.isEmpty() || (roots.size() != 1 && directory == null))
            throw invalid("请选择一个 filesystem 迁移位置，多个位置时指定 --directory: " + roots);
        if (directory != null && !Files.isDirectory(workingDirectory.resolve(directory))) throw invalid("迁移目录不存在: " + directory);
        Path target = directory == null ? roots.get(0) : workingDirectory.resolve(directory).toRealPath();
        Path containing = null;
        for (Path path : roots) {
            if (target.startsWith(path)) {
                if (containing != null) throw invalid("目标匹配多个迁移根目录: " + target);
                containing = path;
            }
        }
        if (containing == null || !Files.isDirectory(target)) throw invalid("--directory 必须位于已配置的迁移目录内");
        // 同一组迁移根目录共用一把进程锁，避免两个开发者同时选到同一版本。
        Path lockPath = Collections.min(roots).resolve(".flydb-new.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            SqlMigrationResolver resolver = new SqlMigrationResolver();
            List<ResolvedMigration> existing = resolver.resolveMigrations(config);
            if (directory == null && config.locations().size() == 1) {
                Set<Path> parents = new LinkedHashSet<Path>();
                for (ResolvedMigration migration : existing) {
                    if (migration.type() == MigrationType.SQL && migration.version() != null)
                        parents.add(target.resolve(migration.script()).getParent());
                }
                if (parents.size() > 1) throw invalid("脚本分布在多个目录，请指定 --directory: " + parents);
                if (!parents.isEmpty()) target = parents.iterator().next();
            }
            String version = requestedVersion == null ? nextVersion(existing, config) : requestedVersion;
            MigrationVersion parsed = MigrationVersion.parse(version);
            for (ResolvedMigration migration : existing) {
                if (migration.type() != MigrationType.UNDO_SQL && parsed.equals(migration.version()))
                    throw new FlydbException(ErrorCode.DUPLICATE_VERSION, "版本 " + version + " 已存在: " + migration.script());
            }
            String name = config.sqlMigrationPrefix() + version + config.sqlMigrationSeparator()
                    + description.trim().replace(' ', '_') + config.sqlMigrationSuffix();
            if (name.contains("/") || name.contains("\\") || Paths.get(name).isAbsolute()) throw invalid("配置的命名规则含路径分隔符");
            Path file = target.resolve(name);
            String relative = containing.relativize(file).toString().replace(File.separatorChar, '/');
            ResolvedMigration candidate = resolver.describeCandidate(config, relative);
            if (candidate == null || candidate.type() != MigrationType.SQL || !parsed.equals(candidate.version()))
                throw invalid("当前发现规则无法识别 " + relative + "，请核对 --directory、命名与路径过滤");
            try {
                Files.createFile(file);
            } catch (FileAlreadyExistsException e) {
                throw new FlydbException(ErrorCode.DUPLICATE_VERSION, "目标文件已存在: " + file, e);
            }
            return new CreatedScript(file, version);
        }
    }

    private static String nextVersion(List<ResolvedMigration> migrations, FlydbConfiguration config) {
        if (config.versionSource() == VersionSource.DIRECTORY || config.migrationOrder() == MigrationOrder.DIRECTORY_VERSION)
            throw invalid("目录版本需要显式指定 --script-version 和对应的 --directory");
        BigInteger max = BigInteger.ZERO;
        int width = 0;
        for (ResolvedMigration migration : migrations) {
            if (migration.version() == null || migration.type() == MigrationType.UNDO_SQL) continue;
            String raw = migration.version().toString();
            if (!raw.matches("[0-9]+") || raw.matches("(?:19|20)[0-9]{6,}"))
                throw invalid("项目使用复合或日期版本，请指定 --script-version");
            max = max.max(new BigInteger(raw));
            if (raw.startsWith("0")) width = Math.max(width, raw.length());
        }
        String next = max.add(BigInteger.ONE).toString();
        return width > next.length() ? String.format(Locale.ROOT, "%0" + width + "d", new BigInteger(next)) : next;
    }

    private static FlydbException invalid(String message) {
        return new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, message);
    }
}
