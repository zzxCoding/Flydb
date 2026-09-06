package com.flydb.web;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import com.flydb.runtime.state.StateJson;

/** Bounded configuration selection using the current operating-system user's access. */
final class LocalFiles {
    private static final Set<String> SKIP = new HashSet<String>(Arrays.asList(".git", "node_modules", "target", ".flydb"));
    private LocalFiles() { }
    static ObjectNode browse(String requested) throws IOException {
        Path directory = ProfileStore.directory(requested);
        List<Path> entries = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (entries.size() >= 300) break;
                if (SKIP.contains(path.getFileName().toString())) continue;
                if (Files.isDirectory(path) || isConfiguration(path)) entries.add(path);
            }
        }
        entries.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        ArrayNode items = StateJson.array();
        for (Path path : entries) items.add(StateJson.object().put("name", path.getFileName().toString())
                .put("path", path.toString()).put("directory", Files.isDirectory(path)));
        ObjectNode result = StateJson.object().put("path", directory.toString())
                .put("parent", directory.getParent() == null ? "" : directory.getParent().toString())
                .put("truncated", entries.size() >= 300);
        result.set("entries", items); return result;
    }
    static ObjectNode discover(String requested) throws IOException {
        Path directory = ProfileStore.directory(requested);
        ArrayNode files = StateJson.array();
        int[] visited = {0}; boolean[] truncated = {false};
        Files.walkFileTree(directory, Collections.<FileVisitOption>emptySet(), 6, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                if (++visited[0] > 10000 || files.size() >= 200) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                return !path.equals(directory) && SKIP.contains(path.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (++visited[0] > 10000 || files.size() >= 200) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                if (attributes.isDirectory()) truncated[0] = true;
                if (attributes.isRegularFile() && isConfiguration(path)) files.add(path.toString());
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { truncated[0] = true; return FileVisitResult.CONTINUE; }
        });
        ObjectNode result = StateJson.object().put("truncated", truncated[0]); result.set("files", files); return result;
    }
    private static boolean isConfiguration(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return Files.isRegularFile(path) && (name.endsWith(".conf") || name.startsWith("flydb") && name.endsWith(".properties"));
    }
}
