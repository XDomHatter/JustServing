package tech.xdomhatter.core.sftp;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TransferServiceTest {

    @Test
    void freeNameWhenNoConflict() {
        assertEquals("a.txt", TransferService.freeName("a.txt", n -> false));
    }

    @Test
    void freeNameWithConflict() {
        Set<String> taken = new HashSet<>();
        taken.add("a.txt");
        assertEquals("a (1).txt", TransferService.freeName("a.txt", taken::contains));
        taken.add("a (1).txt");
        assertEquals("a (2).txt", TransferService.freeName("a.txt", taken::contains));
    }

    @Test
    void freeNameWithoutExtension() {
        Set<String> taken = new HashSet<>();
        taken.add("Makefile");
        assertEquals("Makefile (1)", TransferService.freeName("Makefile", taken::contains));
    }

    @Test
    void freeNameMultiSuffix() {
        Set<String> taken = new HashSet<>();
        taken.add("backup.tar.gz");
        assertEquals("backup.tar (1).gz", TransferService.freeName("backup.tar.gz", taken::contains));
    }
}
