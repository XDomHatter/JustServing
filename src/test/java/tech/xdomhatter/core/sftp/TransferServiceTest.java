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

    // ---------- 看门狗判定 ----------

    private TransferService.Task runningTask(long lastProgress) {
        var t = new TransferService.Task(1, TransferService.Direction.UPLOAD, "p", "src", "dst");
        t.state = TransferService.State.RUNNING;
        t.lastProgress = lastProgress;
        return t;
    }

    @Test
    void checkStaleFailsStalledRunningTask() {
        var t = runningTask(1_000);
        // 恰好等于阈值不判死，超过阈值才判死
        assertFalse(TransferService.checkStale(t, 61_000, 60_000));
        assertTrue(TransferService.checkStale(t, 61_001, 60_000));
        assertEquals(TransferService.State.FAILED, t.state);
        assertTrue(t.cancel, "卡死任务应被标记取消以中断阻塞 IO");
        assertTrue(t.error.contains("超时"), "实际: " + t.error);
    }

    @Test
    void checkStaleLeavesHealthyTaskAlone() {
        var t = runningTask(30_000);
        assertFalse(TransferService.checkStale(t, 61_000, 60_000));
        assertEquals(TransferService.State.RUNNING, t.state);
        assertFalse(t.cancel);
    }

    @Test
    void checkStaleIgnoresNonRunningStates() {
        for (TransferService.State s : TransferService.State.values()) {
            if (s == TransferService.State.RUNNING) continue;
            var t = new TransferService.Task(1, TransferService.Direction.UPLOAD, "p", "src", "dst");
            t.state = s;
            t.lastProgress = 0;
            assertFalse(TransferService.checkStale(t, 999_999, 60_000), s.name());
            assertEquals(s, t.state, s.name());
        }
    }

    @Test
    void checkStaleToleratesNullChannel() {
        var t = runningTask(0);
        assertNull(t.sftp);
        assertTrue(TransferService.checkStale(t, 999_999, 60_000));
        assertEquals(TransferService.State.FAILED, t.state);
    }
}
