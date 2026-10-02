package tech.xdomhatter.core.openwith;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.xdomhatter.core.model.OpenBinding;
import tech.xdomhatter.core.store.ConfigStore;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OpenWithServiceTest {
    @TempDir
    Path dir;

    private OpenWithService service(OpenBinding... bindings) {
        ConfigStore store = new ConfigStore(dir.resolve("cfg-" + System.nanoTime() + ".json"));
        for (OpenBinding b : bindings) {
            store.get().bindings.add(b);
        }
        return new OpenWithService(store);
    }

    private static OpenBinding binding(String pattern, String command) {
        OpenBinding b = new OpenBinding();
        b.pattern = pattern;
        b.command = command;
        return b;
    }

    @Test
    void matchByExtensionPattern() {
        OpenWithService s = service(binding("*.log", "notepad %f"), binding(".txt", "code %f"));
        assertEquals("notepad %f", s.match("server.LOG").orElseThrow().command);
        assertEquals("code %f", s.match("readme.txt").orElseThrow().command);
        assertTrue(s.match("archive.zip").isEmpty());
    }

    @Test
    void orderMattersAndDisabledSkipped() {
        OpenWithService s = service(
                binding("*.log", "first %f"),
                binding("*.log", "second %f"));
        assertEquals("first %f", s.match("a.log").orElseThrow().command);

        OpenBinding disabled = binding("*.log", "nope");
        disabled.enabled = false;
        OpenWithService s2 = service(disabled, binding("*.log", "yes"));
        assertEquals("yes", s2.match("a.log").orElseThrow().command);
    }

    @Test
    void catchAllPattern() {
        OpenWithService s = service(binding("*.md", "editor %f"), binding("*", "fallback %f"));
        assertEquals("fallback %f", s.match("anything.bin").orElseThrow().command);
        assertEquals("editor %f", s.match("doc.md").orElseThrow().command);
    }

    @Test
    void multiSuffixPattern() {
        OpenWithService s = service(binding("*.tar.gz", "archiver %f"));
        assertEquals("archiver %f", s.match("x.tar.gz").orElseThrow().command);
        assertTrue(s.match("x.gz").isEmpty());
    }

    @Test
    void splitCommandRespectsQuotes() {
        assertArrayEquals(new String[]{"a", "b c", "d"}, OpenWithService.splitCommand("a \"b c\" d"));
        assertArrayEquals(new String[]{"C:\\Program Files\\app.exe", "x.txt"},
                OpenWithService.splitCommand("\"C:\\Program Files\\app.exe\" x.txt"));
        assertArrayEquals(new String[]{"cmd"}, OpenWithService.splitCommand("  cmd  "));
        assertArrayEquals(new String[]{}, OpenWithService.splitCommand("   "));
    }
}
