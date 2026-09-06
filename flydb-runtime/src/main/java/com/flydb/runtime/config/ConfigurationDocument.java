package com.flydb.runtime.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Lossless for unchanged Properties entries; saves are conditional on the original bytes. */
public final class ConfigurationDocument {
    private final Path file;
    private final String text;
    private final String revision;
    private final List<Block> blocks;
    private final Map<String, String> values;

    private ConfigurationDocument(Path file, byte[] bytes) throws IOException {
        this.file = file;
        this.text = new String(bytes, StandardCharsets.UTF_8);
        this.revision = digest(bytes);
        this.blocks = split(text);
        this.values = new LinkedHashMap<String, String>();
        for (Block block : blocks) if (block.key != null) values.put(block.key, block.value);
    }

    public static ConfigurationDocument read(Path file) throws IOException {
        Path real = file.toRealPath();
        if (!Files.isRegularFile(real) || Files.size(real) > 2 * 1024 * 1024) {
            throw new IOException("Configuration must be a regular file smaller than 2 MiB");
        }
        return new ConfigurationDocument(real, Files.readAllBytes(real));
    }
    public String revision() { return revision; }
    public String content() { return text; }
    public Map<String, String> values() { return Collections.unmodifiableMap(values); }
    public static ConfigurationDocument parse(String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2 * 1024 * 1024) throw new IOException("Configuration exceeds 2 MiB");
        return new ConfigurationDocument(null, bytes);
    }
    public ConfigurationDocument withEdits(Map<String, String> edits) throws IOException {
        return parse(edit(edits));
    }

    public synchronized ConfigurationDocument save(Map<String, String> edits, String expected) throws IOException {
        return write(edits, null, expected);
    }
    public synchronized ConfigurationDocument saveContent(String content, String expected) throws IOException {
        parse(content);
        return write(null, content, expected);
    }
    private ConfigurationDocument write(Map<String, String> edits, String content, String expected) throws IOException {
        Path lockFile = file.resolveSibling("." + file.getFileName() + ".flydb.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            ConfigurationDocument current = read(file);
            if (!current.revision.equals(expected)) throw new Conflict();
            String updated = content == null ? current.edit(edits) : content;
            // Validate syntax before replacing; the loader validates Flydb keys independently.
            split(updated);
            Path temporary = Files.createTempFile(file.getParent(), ".flydb-save-", ".tmp");
            try {
                Files.write(temporary, updated.getBytes(StandardCharsets.UTF_8));
                try { Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file)); }
                catch (UnsupportedOperationException ignored) { }
                if (!read(file).revision.equals(expected)) throw new Conflict();
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) {
                    throw new IOException("Filesystem does not support atomic configuration saves", e);
                }
            } finally { Files.deleteIfExists(temporary); }
            return read(file);
        }
    }

    private String edit(Map<String, String> edits) {
        String newline = text.contains("\r\n") ? "\r\n" : text.contains("\r") ? "\r" : "\n";
        Map<String, Block> last = new HashMap<String, Block>();
        for (Block block : blocks) if (block.key != null) last.put(block.key, block);
        StringBuilder output = new StringBuilder();
        for (Block block : blocks) {
            if (block.key == null || !edits.containsKey(block.key)
                    || Objects.equals(edits.get(block.key), values.get(block.key))) {
                output.append(block.text);
            } else if (last.get(block.key) == block && edits.get(block.key) != null) {
                output.append(escape(block.key, true)).append('=')
                        .append(escape(edits.get(block.key), false)).append(newline);
            }
        }
        for (Map.Entry<String, String> entry : edits.entrySet()) {
            if (last.containsKey(entry.getKey()) || entry.getValue() == null) continue;
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n'
                    && output.charAt(output.length() - 1) != '\r') output.append(newline);
            output.append(escape(entry.getKey(), true)).append('=')
                    .append(escape(entry.getValue(), false)).append(newline);
        }
        return output.toString();
    }

    private static String escape(String value, boolean key) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') result.append("\\n");
            else if (c == '\r') result.append("\\r");
            else if (c == '\t') result.append("\\t");
            else {
                if (c == '\\' || (c == ' ' && (i == 0 || key)) || (key && "=:#!".indexOf(c) >= 0)) result.append('\\');
                result.append(c);
            }
        }
        return result.toString();
    }

    private static List<Block> split(String text) throws IOException {
        List<Block> result = new ArrayList<Block>();
        java.util.regex.Matcher lines = java.util.regex.Pattern.compile("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").matcher(text);
        StringBuilder logical = new StringBuilder();
        int lineNumber = 1, blockLine = 1;
        while (lines.find()) {
            String line = lines.group();
            if (line.isEmpty()) continue;
            if (logical.length() == 0) blockLine = lineNumber;
            if (line.endsWith("\n") || line.endsWith("\r")) lineNumber++;
            logical.append(line);
            String body = line.replaceFirst("[\\r\\n]+$", "");
            int slashes = 0;
            for (int i = body.length() - 1; i >= 0 && body.charAt(i) == '\\'; i--) slashes++;
            String trimmed = logical.toString().trim();
            if (slashes % 2 == 1 && !trimmed.startsWith("#") && !trimmed.startsWith("!")) continue;
            result.add(block(logical.toString(), blockLine)); logical.setLength(0);
        }
        if (logical.length() > 0) result.add(block(logical.toString(), blockLine));
        return result;
    }

    private static Block block(String text, int line) throws IOException {
        Properties p = new Properties();
        try { p.load(new StringReader(text)); }
        catch (IllegalArgumentException e) { throw new InvalidSyntax(line); }
        String key = p.isEmpty() ? null : p.stringPropertyNames().iterator().next();
        return new Block(text, key, key == null ? null : p.getProperty(key));
    }

    public static String digest(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
                result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static final class Block {
        final String text, key, value;
        Block(String text, String key, String value) { this.text = text; this.key = key; this.value = value; }
    }
    public static final class Conflict extends IOException {
        public Conflict() { super("CONFIG_CONFLICT"); }
    }
    public static final class InvalidSyntax extends IOException {
        private final int line;
        InvalidSyntax(int line) { super("Invalid Properties escape at line " + line); this.line = line; }
        public int line() { return line; }
    }
}
