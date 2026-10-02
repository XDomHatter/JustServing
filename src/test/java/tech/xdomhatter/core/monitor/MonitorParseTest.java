package tech.xdomhatter.core.monitor;

import org.junit.jupiter.api.Test;
import tech.xdomhatter.core.model.MonitorSnapshot;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonitorParseTest {

    private static final String SAMPLE = """
            @@CPU
            cpu  100 0 50 800 10 0 0 0 0 0
            @@MEM
            MemTotal:       1000000 kB
            MemAvailable:    400000 kB
            SwapTotal:             0 kB
            SwapFree:              0 kB
            @@DISK
            Filesystem     1024-blocks     Used Available Capacity Mounted on
            /dev/vda1        41152812 12345678  26664878      32% /
            tmpfs             2020368        0   2020368       0% /dev/shm
            @@PORTS
            tcp   LISTEN 0      128          0.0.0.0:22        0.0.0.0:*     users:(("sshd",pid=800,fd=3))
            udp   UNCONN 0      0            0.0.0.0:68        0.0.0.0:*
            @@PROC
                PID USER                         %CPU %MEM       RSS     ELAPSED COMMAND
              1 root                          0.1  0.5     10240       01:23 systemd
            @@END
            """;

    @Test
    void cpuPercentDelta() {
        String prev = "cpu  100 0 50 800 10 0 0 0 0 0";
        String cur = "cpu  200 0 100 900 10 0 0 0 0 0";
        // total: 960 -> 1210 (+250)；idle+iowait: 810 -> 910 (+100)
        assertEquals(60.0, MonitorService.cpuPercent(prev, cur), 0.001);
        assertEquals(-1, MonitorService.cpuPercent(null, cur), 0.001);
    }

    @Test
    void extractCpuLine() {
        assertEquals("cpu  100 0 50 800 10 0 0 0 0 0", MonitorService.extractCpuLine(SAMPLE));
    }

    @Test
    void fullParseWithoutPrev() {
        MonitorSnapshot s = MonitorService.parse(SAMPLE, null);
        assertEquals(-1, s.cpuPercent);
        assertEquals(1000000L * 1024, s.memTotal);
        assertEquals(400000L * 1024, s.memAvailable);
        assertEquals(1, s.disks.size());
        assertEquals("/", s.disks.get(0).mount);
        assertEquals(41152812L * 1024, s.disks.get(0).total);
        assertEquals(2, s.ports.size());
        assertEquals(22, s.ports.get(0).port);
        assertEquals("sshd", s.ports.get(0).process);
        assertEquals(800, s.ports.get(0).pid);
        assertEquals(1, s.procs.size());
        assertEquals("systemd", s.procs.get(0).command);
        assertNull(s.error);
    }

    @Test
    void fullParseWithPrev() {
        // 上一帧 CPU 累计值更小，可计算出使用率：idle 差 405 / 总差 480 = 15.625%
        MonitorSnapshot s = MonitorService.parse(SAMPLE, "cpu  50 0 25 400 5 0 0 0 0 0");
        assertEquals(15.625, s.cpuPercent, 0.001);
    }

    @Test
    void parseDisksFiltersSpecialFilesystems() {
        List<MonitorSnapshot.Disk> disks = MonitorService.parseDisks(List.of(
                "Filesystem     1024-blocks     Used Available Capacity Mounted on",
                "overlay       100 50 50 50% /",
                "/dev/nvme0n1p1 50000 1000 49000 2% /boot/efi",
                "/dev/sda1 1000 100 900 10% /data"));
        assertEquals(2, disks.size());
        assertEquals("/boot/efi", disks.get(0).mount);
        assertEquals("/data", disks.get(1).mount);
    }

    @Test
    void parsePortsHandlesIPv6() {
        List<MonitorSnapshot.PortListen> ports = MonitorService.parsePorts(List.of(
                "tcp   LISTEN 0 128 [::]:22 [::]:* users:((\"sshd\",pid=1,fd=3))"));
        assertEquals(1, ports.size());
        assertEquals("[::]", ports.get(0).addr);
        assertEquals(22, ports.get(0).port);
    }

    @Test
    void parseProcsSkipsHeader() {
        List<MonitorSnapshot.Proc> procs = MonitorService.parseProcs(List.of(
                "    PID USER                         %CPU %MEM       RSS     ELAPSED COMMAND"));
        assertTrue(procs.isEmpty());
    }
}
