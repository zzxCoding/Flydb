package com.flydb.runtime.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ConfigurationDocumentTest {
    @TempDir Path directory;
    @Test void editsWholeDocumentsWithoutLosingCommentsOrAcceptingAStaleRevision() throws Exception {
        Path file = directory.resolve("raw.conf");
        String initial = "# original\r\nflydb.user=one\r\nflydb.user=two\r\ncustom=first\\\r\n  second\r\n";
        Files.write(file, initial.getBytes("UTF-8"));
        ConfigurationDocument original = ConfigurationDocument.read(file);
        assertThat(ConfigurationDocument.parse(initial).content()).isEqualTo(initial);
        assertThat(ConfigurationDocument.parse(initial).values()).containsEntry("custom", "firstsecond");
        String edited = "# new heading\r\n" + initial;
        original.saveContent(edited, original.revision());
        assertThat(new String(Files.readAllBytes(file), "UTF-8")).isEqualTo(edited);
        assertThatThrownBy(() -> original.saveContent(initial, original.revision())).isInstanceOf(ConfigurationDocument.Conflict.class);
    }
    @Test void preservesCommentsContinuedLinesAndReferencesWhenEditingOneField() throws Exception {
        Path file = directory.resolve("flydb.conf");
        String original = "# 连接\r\nflydb.url=jdbc:mysql://localhost/old\r\n"
                + "flydb.password=${env:DB_PASSWORD}\r\ncustom=one\\\r\n  two\r\n";
        Files.write(file, original.getBytes("UTF-8"));
        ConfigurationDocument doc = ConfigurationDocument.read(file);
        doc.save(Collections.singletonMap("flydb.url", "jdbc:mysql://localhost/new"), doc.revision());
        assertThat(new String(Files.readAllBytes(file), "UTF-8"))
                .isEqualTo(original.replace("localhost/old", "localhost/new"));
    }
    @Test void refusesToOverwriteAnExternalEditAndDoesNotLoseDuplicateKeySemantics() throws Exception {
        Path file = directory.resolve("flydb.conf");
        Files.write(file, "flydb.user=first\n# retain\nflydb.user=last\n".getBytes("UTF-8"));
        ConfigurationDocument old = ConfigurationDocument.read(file);
        old.save(Collections.singletonMap("flydb.user", "new user"), old.revision());
        assertThat(ConfigurationDocument.read(file).values()).containsEntry("flydb.user", "new user");
        assertThatThrownBy(() -> old.save(Collections.singletonMap("flydb.user", "bad"), old.revision()))
                .isInstanceOf(ConfigurationDocument.Conflict.class);
        assertThat(new String(Files.readAllBytes(file), "UTF-8")).contains("# retain");
    }
}
