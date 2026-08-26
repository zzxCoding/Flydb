package com.flydb.cli;

import java.io.Console;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import com.flydb.cli.config.CliConfiguration;
import com.flydb.cli.config.ConfigLoader;
import com.flydb.cli.driver.DriverContext;
import com.flydb.cli.driver.DriverLoader;
import com.flydb.cli.init.InitScaffolder;
import com.flydb.cli.output.InfoTableRenderer;
import com.flydb.cli.output.SecretRedactor;
import com.flydb.cli.output.json.JsonRenderers;
import com.flydb.core.Flydb;
import com.flydb.core.api.MigrateResult;
import com.flydb.core.api.DryRunMigration;
import com.flydb.core.api.DryRunResult;
import com.flydb.core.api.DryRunStatement;
import com.flydb.core.api.RepairResult;
import com.flydb.core.api.UndoResult;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;
import com.flydb.core.exception.FlydbValidationException;
import com.flydb.core.exception.ValidationProblem;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** Flydb 独立命令行入口。 */
public final class FlydbCli {

    private final PrintWriter out;
    private final PrintWriter err;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private final Path installDirectory;
    private final InterruptCoordinator interrupts;

    public FlydbCli() {
        this(new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true),
                new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true),
                System.getenv(), Paths.get(".").toAbsolutePath().normalize(),
                detectInstallDirectory());
    }

    FlydbCli(PrintWriter out, PrintWriter err, Map<String, String> environment,
             Path workingDirectory, Path installDirectory) {
        this.out = out;
        this.err = err;
        this.environment = environment;
        this.workingDirectory = workingDirectory;
        this.installDirectory = installDirectory;
        this.interrupts = new InterruptCoordinator(System::exit);
    }

    public int execute(String... args) {
        RootCommand root = new RootCommand();
        CommandLine commandLine = new CommandLine(root);
        commandLine.setOut(out);
        commandLine.setErr(err);
        commandLine.setExecutionExceptionHandler((error, command, parseResult) ->
                handleFailure(error, root, command));
        commandLine.setParameterExceptionHandler((error, arguments) -> {
            String message = SecretRedactor.redact(error.getMessage());
            err.println(message);
            if (jsonRequested(arguments)) {
                out.println(JsonRenderers.error(commandName(error.getCommandLine()), 4,
                        null, message, null));
            }
            return 4;
        });
        return commandLine.execute(args);
    }

    private static boolean jsonRequested(String... arguments) {
        for (String argument : arguments) {
            if ("--json".equals(argument)) return true;
        }
        return false;
    }

    public static void main(String[] args) {
        FlydbCli cli = new FlydbCli();
        cli.interrupts.install();
        System.exit(cli.execute(args));
    }

    @Command(name = "flydb", mixinStandardHelpOptions = true,
            description = "面向各类 JDBC 数据库的 Schema 版本化迁移工具",
            subcommands = {MigrateCommand.class, InfoCommand.class, ValidateCommand.class,
                    BaselineCommand.class, RepairCommand.class, CleanCommand.class,
                    UndoCommand.class, InitCommand.class, VersionCommand.class})
    final class RootCommand implements Runnable {
        @Option(names = {"-c", "--config"}, description = "显式配置文件",
                scope = CommandLine.ScopeType.INHERIT)
        Path config;
        @Option(names = {"-u", "--url"}, description = "JDBC URL",
                scope = CommandLine.ScopeType.INHERIT)
        String url;
        @Option(names = "--user", description = "数据库用户",
                scope = CommandLine.ScopeType.INHERIT)
        String user;
        @Option(names = {"-p", "--password"}, description = "数据库密码（推荐改用环境变量或密码文件）",
                scope = CommandLine.ScopeType.INHERIT)
        String password;
        @Option(names = "--driver", description = "JDBC Driver 类名",
                scope = CommandLine.ScopeType.INHERIT)
        String driver;
        @Option(names = "--driver-coordinate", description = "驱动 Maven 坐标 groupId:artifactId:version",
                scope = CommandLine.ScopeType.INHERIT)
        String driverCoordinate;
        @Option(names = "--driver-download", description = "驱动下载策略：auto|never",
                scope = CommandLine.ScopeType.INHERIT)
        String driverDownload;
        @Option(names = "--driver-cache", description = "Flydb 驱动下载缓存目录",
                scope = CommandLine.ScopeType.INHERIT)
        Path driverCache;
        @Option(names = "--maven-settings", description = "Maven settings.xml 路径",
                scope = CommandLine.ScopeType.INHERIT)
        Path mavenSettings;
        @Option(names = "--maven-local-repository", description = "Maven 本地仓库目录",
                scope = CommandLine.ScopeType.INHERIT)
        Path mavenLocalRepository;
        @Option(names = "--offline", arity = "0..1", fallbackValue = "true",
                description = "禁止联网解析 JDBC 驱动",
                scope = CommandLine.ScopeType.INHERIT)
        Boolean offline;
        @Option(names = "--database-type", description = "显式方言名",
                scope = CommandLine.ScopeType.INHERIT)
        String databaseType;
        @Option(names = {"-l", "--locations"}, description = "迁移位置，逗号分隔",
                scope = CommandLine.ScopeType.INHERIT)
        String locations;
        @Option(names = "--encoding", description = "SQL 文件编码",
                scope = CommandLine.ScopeType.INHERIT)
        String encoding;
        @Option(names = "--table", description = "Schema 历史表名",
                scope = CommandLine.ScopeType.INHERIT)
        String table;
        @Option(names = "--baseline-version", description = "基准版本",
                scope = CommandLine.ScopeType.INHERIT)
        String baselineVersion;
        @Option(names = "--baseline-on-migrate", arity = "0..1", fallbackValue = "true",
                description = "非空库首次 migrate 时自动 baseline",
                scope = CommandLine.ScopeType.INHERIT)
        Boolean baselineOnMigrate;
        @Option(names = "--validate-on-migrate", arity = "0..1", fallbackValue = "true",
                description = "migrate 前执行校验",
                scope = CommandLine.ScopeType.INHERIT)
        Boolean validateOnMigrate;
        @Option(names = "--out-of-order", arity = "0..1", fallbackValue = "true",
                description = "允许补执行低版本脚本",
                scope = CommandLine.ScopeType.INHERIT)
        Boolean outOfOrder;
        @Option(names = "--target-version", description = "只执行指定的精确版本",
                scope = CommandLine.ScopeType.INHERIT)
        String targetVersion;
        @Option(names = "--start-version", description = "执行范围起始版本（包含边界）",
                scope = CommandLine.ScopeType.INHERIT)
        String startVersion;
        @Option(names = "--end-version",
                description = "执行范围结束版本（含该版本，不含其 .N 子版本；需包含时用 --version-selection family-range）",
                scope = CommandLine.ScopeType.INHERIT)
        String endVersion;
        @Option(names = "--version-selection",
                description = "版本筛选：exact|range|family|family-range|regex",
                scope = CommandLine.ScopeType.INHERIT)
        String versionSelection;
        @Option(names = "--version-source", description = "版本来源：file|directory",
                scope = CommandLine.ScopeType.INHERIT)
        String versionSource;
        @Option(names = "--version-regex", description = "版本正则（整串匹配）",
                scope = CommandLine.ScopeType.INHERIT)
        String versionRegex;
        @Option(names = "--directory-glob", description = "相对父目录 glob",
                scope = CommandLine.ScopeType.INHERIT)
        String directoryGlob;
        @Option(names = "--file-glob", description = "文件名 glob",
                scope = CommandLine.ScopeType.INHERIT)
        String fileGlob;
        @Option(names = "--path-glob", description = "完整相对路径 glob",
                scope = CommandLine.ScopeType.INHERIT)
        String pathGlob;
        @Option(names = "--directory-regex", description = "相对父目录正则（整串匹配）",
                scope = CommandLine.ScopeType.INHERIT)
        String directoryRegex;
        @Option(names = "--file-regex", description = "文件名正则（整串匹配）",
                scope = CommandLine.ScopeType.INHERIT)
        String fileRegex;
        @Option(names = "--path-regex", description = "完整相对路径正则（整串匹配）",
                scope = CommandLine.ScopeType.INHERIT)
        String pathRegex;
        @Option(names = "--migration-order",
                description = "迁移排序：version|directory-version",
                scope = CommandLine.ScopeType.INHERIT)
        String migrationOrder;
        @Option(names = "--directory-version-regex",
                description = "目录版本提取正则，使用 version 命名组或第一个捕获组",
                scope = CommandLine.ScopeType.INHERIT)
        String directoryVersionRegex;
        @Option(names = "-D", description = "迁移占位符 key=value",
                scope = CommandLine.ScopeType.INHERIT)
        Map<String, String> placeholders = new LinkedHashMap<String, String>();
        @Option(names = "--placeholder-replacement", arity = "0..1", fallbackValue = "true",
                description = "是否替换 SQL 占位符",
                scope = CommandLine.ScopeType.INHERIT)
        Boolean placeholderReplacement;
        @Option(names = "--placeholder-prefix", description = "占位符前缀",
                scope = CommandLine.ScopeType.INHERIT)
        String placeholderPrefix;
        @Option(names = "--placeholder-suffix", description = "占位符后缀",
                scope = CommandLine.ScopeType.INHERIT)
        String placeholderSuffix;
        @Option(names = "--sql-migration-prefix", description = "版本化脚本前缀",
                scope = CommandLine.ScopeType.INHERIT)
        String sqlMigrationPrefix;
        @Option(names = "--repeatable-migration-prefix", description = "可重复脚本前缀",
                scope = CommandLine.ScopeType.INHERIT)
        String repeatableMigrationPrefix;
        @Option(names = "--undo-migration-prefix", description = "撤销脚本前缀",
                scope = CommandLine.ScopeType.INHERIT)
        String undoMigrationPrefix;
        @Option(names = "--sql-migration-separator", description = "版本与描述分隔符",
                scope = CommandLine.ScopeType.INHERIT)
        String sqlMigrationSeparator;
        @Option(names = "--sql-migration-suffix", description = "SQL 脚本后缀",
                scope = CommandLine.ScopeType.INHERIT)
        String sqlMigrationSuffix;
        @Option(names = "--callbacks", description = "Callback 类名，逗号分隔",
                scope = CommandLine.ScopeType.INHERIT)
        String callbacks;
        @Option(names = "--clean-disabled", arity = "0..1", fallbackValue = "true",
                description = "clean 安全开关",
                scope = CommandLine.ScopeType.INHERIT)
        Boolean cleanDisabled;
        @Option(names = "--lock-timeout-seconds", description = "锁等待秒数",
                scope = CommandLine.ScopeType.INHERIT)
        Integer lockTimeoutSeconds;
        @Option(names = "--batch-size", description = "SQL 语句 JDBC 批大小（默认 1 逐条执行）",
                scope = CommandLine.ScopeType.INHERIT)
        Integer batchSize;
        @Option(names = {"-X", "--debug"}, description = "输出完整异常栈",
                scope = CommandLine.ScopeType.INHERIT)
        boolean debug;
        @Option(names = {"-q", "--quiet"}, description = "仅输出必要结果和错误",
                scope = CommandLine.ScopeType.INHERIT)
        boolean quiet;
        @Option(names = "--color", defaultValue = "auto", description = "颜色：auto|always|never",
                scope = CommandLine.ScopeType.INHERIT)
        String color;
        @Option(names = {"-n", "--dry-run"}, description = "migrate/undo 只解析并打印 SQL",
                scope = CommandLine.ScopeType.INHERIT)
        boolean dryRun;
        @Option(names = "--json", description = "机器可读 JSON 输出：stdout 单行信封，stderr 仅诊断",
                scope = CommandLine.ScopeType.INHERIT)
        boolean json;

        @Override public void run() {
            new CommandLine(this).usage(out);
        }

        PrintWriter out() { return FlydbCli.this.out; }
        Path workingDirectory() { return FlydbCli.this.workingDirectory; }

        ConfiguredFlydb open() {
            Map<String, String> overrides = overrides();
            CliConfiguration configuration = new ConfigLoader().load(config, workingDirectory,
                    installDirectory, environment, overrides);
            if (configuration.url() == null || configuration.url().trim().isEmpty()) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "必须提供 flydb.url（可用 --url、FLYDB_URL 或 flydb.conf 提供；"
                                + "配置文件查找顺序：--config 指定文件、当前目录 "
                                + workingDirectory + " 下的 flydb.conf、"
                                + "安装目录 conf/flydb.conf）");
            }
            configuration = promptForPassword(configuration, overrides);
            DriverContext driver = new DriverLoader().open(
                    installDirectory.resolve("drivers"), configuration);
            ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(driver.classLoader());
            try {
                ConfiguredFlydb configured = new ConfiguredFlydb(driver, configuration,
                        new Flydb(configuration.toCoreConfiguration(
                                driver.dataSource(), driver.classLoader())), previousClassLoader,
                        FlydbCli.this.interrupts);
                FlydbCli.this.interrupts.register(configured);
                return configured;
            } catch (RuntimeException e) {
                Thread.currentThread().setContextClassLoader(previousClassLoader);
                driver.close();
                throw e;
            }
        }

        private CliConfiguration promptForPassword(CliConfiguration current,
                                                   Map<String, String> overrides) {
            if (current.password() != null) return current;
            Console console = json ? null : System.console();
            if (console == null) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "非交互终端缺少密码；请在 flydb.conf 配置 flydb.password，或使用 --password、FLYDB_PASSWORD、"
                                + "${env:VAR} 或 flydb.password.file");
            }
            char[] passwordValue = console.readPassword("数据库密码: ");
            if (passwordValue == null) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG, "未读取到数据库密码");
            }
            overrides.put("flydb.password", new String(passwordValue));
            java.util.Arrays.fill(passwordValue, '\0');
            return new ConfigLoader().load(config, workingDirectory, installDirectory,
                    environment, overrides);
        }

        private Map<String, String> overrides() {
            Map<String, String> values = new LinkedHashMap<String, String>();
            put(values, "flydb.url", url); put(values, "flydb.user", user);
            put(values, "flydb.password", password); put(values, "flydb.driver", driver);
            put(values, "flydb.driver-coordinate", driverCoordinate);
            put(values, "flydb.driver-download", driverDownload);
            put(values, "flydb.driver-cache", driverCache);
            put(values, "flydb.maven-settings", mavenSettings);
            put(values, "flydb.maven-local-repository", mavenLocalRepository);
            put(values, "flydb.offline", offline);
            put(values, "flydb.database-type", databaseType);
            put(values, "flydb.locations", locations); put(values, "flydb.encoding", encoding);
            put(values, "flydb.table", table); put(values, "flydb.baseline-version", baselineVersion);
            put(values, "flydb.baseline-on-migrate", baselineOnMigrate);
            put(values, "flydb.validate-on-migrate", validateOnMigrate);
            put(values, "flydb.out-of-order", outOfOrder);
            put(values, "flydb.target-version", targetVersion);
            put(values, "flydb.start-version", startVersion);
            put(values, "flydb.end-version", endVersion);
            put(values, "flydb.version-selection", versionSelection);
            put(values, "flydb.version-source", versionSource);
            put(values, "flydb.version-regex", versionRegex);
            put(values, "flydb.directory-glob", directoryGlob);
            put(values, "flydb.file-glob", fileGlob);
            put(values, "flydb.path-glob", pathGlob);
            put(values, "flydb.directory-regex", directoryRegex);
            put(values, "flydb.file-regex", fileRegex);
            put(values, "flydb.path-regex", pathRegex);
            put(values, "flydb.migration-order", migrationOrder);
            put(values, "flydb.directory-version-regex", directoryVersionRegex);
            put(values, "flydb.placeholder-replacement", placeholderReplacement);
            put(values, "flydb.placeholder-prefix", placeholderPrefix);
            put(values, "flydb.placeholder-suffix", placeholderSuffix);
            put(values, "flydb.sql-migration-prefix", sqlMigrationPrefix);
            put(values, "flydb.repeatable-migration-prefix", repeatableMigrationPrefix);
            put(values, "flydb.undo-migration-prefix", undoMigrationPrefix);
            put(values, "flydb.sql-migration-separator", sqlMigrationSeparator);
            put(values, "flydb.sql-migration-suffix", sqlMigrationSuffix);
            put(values, "flydb.callbacks", callbacks); put(values, "flydb.clean-disabled", cleanDisabled);
            put(values, "flydb.lock-timeout-seconds", lockTimeoutSeconds);
            put(values, "flydb.batch-size", batchSize);
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                values.put("flydb.placeholders." + entry.getKey(), entry.getValue());
            }
            return values;
        }
    }

    private abstract static class DatabaseCommand implements Callable<Integer> {
        @ParentCommand RootCommand root;

        @Override public final Integer call() {
            try (ConfiguredFlydb configured = root.open()) {
                run(configured);
                return 0;
            }
        }

        abstract void run(ConfiguredFlydb configured);
    }

    @Command(name = "migrate", mixinStandardHelpOptions = true,
            description = "执行待应用的迁移")
    static final class MigrateCommand extends DatabaseCommand {
        @Override void run(ConfiguredFlydb configured) {
            if (root.dryRun) {
                DryRunResult result = configured.flydb.dryRunMigrate();
                if (root.json) {
                    root.out().println(JsonRenderers.dryRun("migrate", result,
                            configured.configuration.password()));
                } else {
                    printDryRun(root, result, configured.configuration.password());
                }
                return;
            }
            MigrateResult result = configured.flydb.migrate();
            if (root.json) {
                root.out().println(JsonRenderers.migrate(result));
                return;
            }
            if (!root.quiet) rootLine(root, "迁移完成，执行 " + result.executed().size() + " 个脚本");
            for (String warning : result.warnings()) rootLine(root, "警告: " + warning);
        }
    }

    @Command(name = "info", mixinStandardHelpOptions = true,
            description = "查看迁移状态总表")
    static final class InfoCommand extends DatabaseCommand {
        @Override void run(ConfiguredFlydb configured) {
            com.flydb.core.api.MigrationInfoService information = configured.flydb.info();
            if (root.json) {
                root.out().println(JsonRenderers.info(information.databaseName(),
                        configured.configuration.url(), configured.configuration.table(),
                        information));
                return;
            }
            boolean color = colorEnabled(root.color);
            root.out().print(new InfoTableRenderer().render(version(), information.databaseName(),
                    configured.configuration.url(), configured.configuration.table(),
                    information, color));
            root.out().flush();
        }
    }

    @Command(name = "validate", mixinStandardHelpOptions = true,
            description = "校验本地脚本与历史记录一致性")
    static final class ValidateCommand extends DatabaseCommand {
        @Override void run(ConfiguredFlydb configured) {
            configured.flydb.validate();
            if (root.json) {
                root.out().println(JsonRenderers.validate());
                return;
            }
            if (!root.quiet) rootLine(root, "校验通过");
        }
    }

    @Command(name = "baseline", mixinStandardHelpOptions = true,
            description = "为存量库设置基准版本")
    static final class BaselineCommand extends DatabaseCommand {
        @Override void run(ConfiguredFlydb configured) {
            configured.flydb.baseline();
            if (root.json) {
                root.out().println(JsonRenderers.baseline(
                        configured.configuration.baselineVersion()));
                return;
            }
            if (!root.quiet) rootLine(root, "基准版本已写入");
        }
    }

    @Command(name = "repair", mixinStandardHelpOptions = true,
            description = "清除失败记录并对齐校验和")
    static final class RepairCommand extends DatabaseCommand {
        @Override void run(ConfiguredFlydb configured) {
            RepairResult result = configured.flydb.repair();
            if (root.json) {
                root.out().println(JsonRenderers.repair(result));
                return;
            }
            if (!root.quiet) rootLine(root, "修复完成，清除失败记录 "
                    + result.removedFailedRecords().size() + " 条，对齐校验和 "
                    + result.alignedChecksums().size() + " 条");
        }
    }

    @Command(name = "clean", mixinStandardHelpOptions = true,
            description = "清空目标 schema（默认禁用）")
    static final class CleanCommand extends DatabaseCommand {
        @Option(names = "--force", description = "非交互环境确认清空") boolean force;

        @Override void run(ConfiguredFlydb configured) {
            if (!force) {
                Console console = root.json ? null : System.console();
                if (console == null) {
                    throw new FlydbException(ErrorCode.CLEAN_DISABLED,
                            "非交互执行 clean 必须同时设置 flydb.clean-disabled=false 并使用 --force");
                }
                String databaseName = databaseName(configured.configuration.url());
                String confirmation = console.readLine("clean 将清空目标库，请输入库名 “"
                        + databaseName + "” 确认: ");
                if (!databaseName.equals(confirmation)) {
                    throw new FlydbException(ErrorCode.CLEAN_DISABLED,
                            "输入的目标库名不匹配，已拒绝 clean");
                }
            }
            configured.flydb.clean();
            if (root.json) {
                root.out().println(JsonRenderers.clean());
                return;
            }
            if (!root.quiet) rootLine(root, "目标 schema 已清空");
        }
    }

    @Command(name = "undo", mixinStandardHelpOptions = true,
            description = "撤销最近一次版本化迁移")
    static final class UndoCommand extends DatabaseCommand {
        @Override void run(ConfiguredFlydb configured) {
            if (root.dryRun) {
                DryRunResult result = configured.flydb.dryRunUndo();
                if (root.json) {
                    root.out().println(JsonRenderers.dryRun("undo", result,
                            configured.configuration.password()));
                } else {
                    printDryRun(root, result, configured.configuration.password());
                }
                return;
            }
            UndoResult result = configured.flydb.undo();
            if (root.json) {
                root.out().println(JsonRenderers.undo(result));
                return;
            }
            if (!root.quiet) rootLine(root, "已撤销版本 " + result.undoneVersion());
        }
    }

    @Command(name = "init", mixinStandardHelpOptions = true,
            description = "初始化配置、迁移目录与驱动说明")
    static final class InitCommand implements Callable<Integer> {
        @ParentCommand RootCommand root;
        @Option(names = "--yes", description = "非交互确认") boolean yes;

        @Override public Integer call() {
            String initUrl = root.url;
            String initUser = root.user;
            String initDriver = root.driver;
            String initDatabaseType = root.databaseType;
            if (!yes) {
                Console console = root.json ? null : System.console();
                if (console == null) {
                    throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                            "非交互执行 init 必须使用 --yes 并提供 --url");
                }
                initUrl = prompt(console, "JDBC URL", initUrl);
                initUser = prompt(console, "数据库用户", initUser);
                initDriver = prompt(console, "JDBC 驱动类名（可留空自动推断）", initDriver);
                initDatabaseType = prompt(console, "数据库类型（可留空自动探测）",
                        initDatabaseType);
                String confirmation = console.readLine("在当前目录创建 Flydb 文件？[y/N] ");
                if (!"y".equalsIgnoreCase(confirmation)
                        && !"yes".equalsIgnoreCase(confirmation)) {
                    throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                            "已取消 init");
                }
            }
            if (initUrl == null || initUrl.trim().isEmpty()) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "init 必须提供 JDBC URL");
            }
            List<Path> created = new InitScaffolder().create(root.workingDirectory(),
                    initUrl, initUser, initDriver, initDatabaseType);
            if (root.json) {
                List<String> createdFiles = new ArrayList<String>();
                for (Path file : created) {
                    createdFiles.add(root.workingDirectory().relativize(file).toString());
                }
                root.out().println(JsonRenderers.init(createdFiles));
            } else if (!root.quiet) {
                root.out().println("已生成 Flydb 工程骨架:");
                for (Path file : created) {
                    root.out().println("  " + root.workingDirectory().relativize(file));
                }
            }
            return 0;
        }

        private static String prompt(Console console, String label, String current) {
            String suffix = current == null || current.isEmpty() ? ": " : " [" + current + "]: ";
            String value = console.readLine(label + suffix);
            return value == null || value.trim().isEmpty() ? current : value.trim();
        }
    }

    @Command(name = "version", mixinStandardHelpOptions = true,
            description = "输出 flydb 自身版本")
    static final class VersionCommand implements Runnable {
        @ParentCommand RootCommand root;
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;

        @Override public void run() {
            if (root.json) {
                spec.commandLine().getOut().println(JsonRenderers.version(version()));
            } else {
                spec.commandLine().getOut().println("flydb " + version());
            }
        }
    }

    private int handleFailure(Throwable error, RootCommand root, CommandLine command) {
        Throwable cause = unwrap(error);
        String message = SecretRedactor.redact(
                cause.getMessage() == null ? cause.toString() : cause.getMessage());
        err.println(message);
        if (root.debug) cause.printStackTrace(err);
        int exitCode = exitCode(cause);
        if (root.json) {
            out.println(jsonError(commandName(command), exitCode, cause, message));
        }
        return exitCode;
    }

    private static String jsonError(String command, int exitCode, Throwable cause,
                                    String fallbackDetail) {
        String code = null;
        String detail = fallbackDetail;
        List<ValidationProblem> problems = Collections.emptyList();
        if (cause instanceof FlydbException) {
            FlydbException flydbError = (FlydbException) cause;
            code = flydbError.errorCode().code();
            detail = SecretRedactor.redact(flydbError.detail());
            if (cause instanceof FlydbValidationException) {
                problems = ((FlydbValidationException) cause).problems();
            }
        }
        return JsonRenderers.error(command, exitCode, code, detail, problems);
    }

    /** 从 picocli 的限定名取叶子命令名；根命令或未知场景返回 null。 */
    private static String commandName(CommandLine command) {
        if (command == null) return null;
        String qualified = command.getCommandName();
        int space = qualified.lastIndexOf(' ');
        String leaf = space >= 0 ? qualified.substring(space + 1) : qualified;
        return "flydb".equals(leaf) ? null : leaf;
    }

    static int exitCode(Throwable cause) {
        if (cause instanceof FlydbValidationException) return 2;
        if (cause instanceof FlydbException) {
            String code = ((FlydbException) cause).errorCode().code();
            if (code.startsWith("FLYDB-3")) return 3;
            if (code.startsWith("FLYDB-4")) return 4;
        }
        return 1;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof CommandLine.ExecutionException
                || current instanceof java.lang.reflect.InvocationTargetException)) {
            current = current.getCause();
        }
        return current;
    }

    private static void rootLine(RootCommand root, String text) {
        if (!root.quiet) root.out().println(text);
    }

    static void printDryRun(RootCommand root, DryRunResult result, String password) {
        com.flydb.core.api.PlanArtifact plan = com.flydb.core.api.PlanArtifact.of(result);
        rootLine(root, "预演完成：" + result.migrations().size() + " 个脚本，仅打印、不执行");
        root.out().println("计划 " + com.flydb.core.api.PlanArtifact.ALGORITHM + "/" + plan.id()
                + "（" + plan.statementCount() + " 条语句"
                + (plan.targetVersion() == null ? "" : "，目标版本 " + plan.targetVersion())
                + "）");
        for (DryRunMigration migration : result.migrations()) {
            root.out().println("-- " + migration.script() + " [" + migration.type() + "]");
            for (DryRunStatement statement : migration.statements()) {
                root.out().println("-- 起始行 " + statement.lineNumber());
                root.out().println(SecretRedactor.redactSecret(statement.sql(), password) + ";");
            }
        }
    }

    private static boolean colorEnabled(String value) {
        if ("always".equals(value)) return true;
        if ("never".equals(value)) return false;
        if ("auto".equals(value)) return System.console() != null;
        throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                "--color 仅支持 auto、always、never: " + value);
    }

    private static String databaseName(String url) {
        String withoutQuery = url == null ? "" : url.split("[?;]", 2)[0];
        int slash = withoutQuery.lastIndexOf('/');
        String value = slash >= 0 ? withoutQuery.substring(slash + 1) : withoutQuery;
        return value.isEmpty() ? "<default>" : value;
    }

    private static void put(Map<String, String> values, String key, Object value) {
        if (value != null) values.put(key, String.valueOf(value));
    }

    private static String version() {
        Package pkg = FlydbCli.class.getPackage();
        String implementation = pkg == null ? null : pkg.getImplementationVersion();
        return implementation != null ? implementation : "0.3.2";
    }

    private static Path detectInstallDirectory() {
        try {
            Path location = Paths.get(FlydbCli.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path parent = java.nio.file.Files.isDirectory(location) ? location : location.getParent();
            return parent != null && parent.getFileName() != null
                    && "lib".equals(parent.getFileName().toString()) ? parent.getParent() : parent;
        } catch (Exception ignored) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
    }

    private static final class ConfiguredFlydb implements AutoCloseable {
        private final DriverContext driver;
        private final CliConfiguration configuration;
        private final Flydb flydb;
        private final ClassLoader previousClassLoader;
        private final InterruptCoordinator interrupts;

        private ConfiguredFlydb(DriverContext driver, CliConfiguration configuration, Flydb flydb,
                                ClassLoader previousClassLoader,
                                InterruptCoordinator interrupts) {
            this.driver = driver;
            this.configuration = configuration;
            this.flydb = flydb;
            this.previousClassLoader = previousClassLoader;
            this.interrupts = interrupts;
        }

        @Override public void close() {
            interrupts.clear(this);
            try {
                driver.close();
            } finally {
                Thread.currentThread().setContextClassLoader(previousClassLoader);
            }
        }
    }
}
