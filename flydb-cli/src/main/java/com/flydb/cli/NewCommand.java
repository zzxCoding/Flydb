package com.flydb.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import com.flydb.runtime.init.MigrationScriptCreator;
import com.flydb.runtime.output.json.JsonRenderers;
import picocli.CommandLine.*;

/** 只创建待编辑的空脚本，使用与迁移相同的发现及版本规则。 */
@Command(name = "new", mixinStandardHelpOptions = true, description = "离线创建下一份版本化 SQL")
final class NewCommand implements Callable<Integer> {
    @ParentCommand FlydbCli.RootCommand root;
    @Parameters(index = "0", paramLabel = "DESCRIPTION") String description;
    @Option(names = "--script-version", description = "显式脚本版本") String requestedVersion;
    @Option(names = "--directory", description = "已有迁移根目录或其子目录") Path directory;

    @Override public Integer call() throws IOException {
        MigrationScriptCreator.CreatedScript created = MigrationScriptCreator.create(root.discoveryConfiguration(),
                root.workingDirectory(), description, requestedVersion, directory);
        if (root.json) root.out().println(JsonRenderers.newScript(created.path().toString(), created.version()));
        else if (!root.quiet) root.out().println(created.path());
        return 0;
    }
}
