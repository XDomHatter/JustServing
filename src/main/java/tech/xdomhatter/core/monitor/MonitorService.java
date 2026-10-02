package tech.xdomhatter.core.monitor;

import com.jcraft.jsch.Session;
import tech.xdomhatter.core.model.MonitorSnapshot;
import tech.xdomhatter.core.ssh.SshManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 服务器监控：一次 SSH exec 批量采集 CPU/RAM/磁盘/端口/进程，减少往返。
 * 仅支持 Linux 服务器。
 */
public class MonitorService {
    public interface Listener {
        void onSnapshot(String profileId, MonitorSnapshot snapshot);

        default void onError(String profileId, String message) {}
    }

    public static final String MON_CMD =
            "echo '@@CPU'; grep '^cpu ' /proc/stat; "
                    + "echo '@@MEM'; grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo; "
                    + "echo '@@DISK'; df -P 2>/dev/null; "
                    + "echo '@@PORTS'; ss -tulpnH 2>/dev/null; "
                    + "echo '@@PROC'; ps -eo pid,user:24,pcpu,pmem,rss:10,etime,comm:24 --sort=-pcpu 2>/dev/null | head -n 26; "
                    + "echo '@@END'";

    private static final Set<String> DF_SKIP_FS = Set.of(
            "tmpfs", "devtmpfs", "udev", "overlay", "squashfs", "shm", "efivarfs", "cgroup",
            "cgroup2", "proc", "sysfs", "devpts", "mqueue", "hugetlbfs", "securityfs", "debugfs",
            "tracefs", "fusectl", "configfs", "pstore", "bpf", "autofs", "binfmt_misc", "ramfs", "none");

    private final SshManager ssh;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monitor");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ScheduledFuture<?>> jobs = new HashMap<>();
    private final Map<String, String> prevCpu = new HashMap<>();
    private volatile int intervalMs = 3000;

    public MonitorService(SshManager ssh) {
        this.ssh = ssh;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void setInterval(int ms) {
        intervalMs = Math.max(1000, ms);
    }

    public synchronized void start(String profileId) {
        if (jobs.containsKey(profileId)) return;
        ScheduledFuture<?> f = ses.scheduleWithFixedDelay(() -> sample(profileId), 0, intervalMs, TimeUnit.MILLISECONDS);
        jobs.put(profileId, f);
    }

    public synchronized void stop(String profileId) {
        ScheduledFuture<?> f = jobs.remove(profileId);
        if (f != null) f.cancel(false);
        prevCpu.remove(profileId);
    }

    public synchronized void stopAll() {
        jobs.values().forEach(f -> f.cancel(false));
        jobs.clear();
        prevCpu.clear();
    }

    public boolean isRunning(String profileId) {
        return jobs.containsKey(profileId);
    }

    private void sample(String profileId) {
        try {
            Session s = ssh.sessionQuiet(profileId);
            if (s == null) return;
            SshManager.ExecResult r = SshManager.exec(s, MON_CMD, Math.max(8000, intervalMs * 3L));
            if (r.exitCode() != 0 && r.stdout().isBlank()) {
                error(profileId, "监控命令失败: " + r.stderr().strip());
                return;
            }
            MonitorSnapshot snap = parse(r.stdout(), prevCpu.get(profileId));
            String cpuLine = extractCpuLine(r.stdout());
            if (cpuLine != null) prevCpu.put(profileId, cpuLine);
            if (snap.error != null) error(profileId, snap.error);
            else {
                for (Listener l : listeners) l.onSnapshot(profileId, snap);
            }
        } catch (Exception e) {
            error(profileId, "监控采样失败: " + e.getMessage());
        }
    }

    private void error(String profileId, String msg) {
        for (Listener l : listeners) l.onError(profileId, msg);
    }

    static String extractCpuLine(String raw) {
        boolean inSection = false;
        for (String line : raw.split("\n")) {
            String t = line.strip();
            if (t.equals("@@CPU")) {
                inSection = true;
                continue;
            }
            if (t.startsWith("@@")) inSection = false;
            if (inSection && t.startsWith("cpu ")) return t;
        }
        return null;
    }

    public static MonitorSnapshot parse(String raw, String prevCpuLine) {
        MonitorSnapshot snap = new MonitorSnapshot();
        snap.timestamp = System.currentTimeMillis();
        Map<String, List<String>> sections = sectionize(raw);
        List<String> cpu = sections.get("CPU");
        String cur = cpu == null ? null : cpu.stream().filter(l -> l.strip().startsWith("cpu ")).findFirst().orElse(null);
        if (cur != null && prevCpuLine != null) {
            snap.cpuPercent = cpuPercent(prevCpuLine, cur);
        }
        List<String> mem = sections.getOrDefault("MEM", List.of());
        Map<String, Long> memMap = new HashMap<>();
        for (String line : mem) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            try {
                memMap.put(line.substring(0, i).strip(), Long.parseLong(line.substring(i + 1).strip().split("\\s+")[0]));
            } catch (NumberFormatException ignored) {
            }
        }
        snap.memTotal = kB(memMap.get("MemTotal"));
        snap.memAvailable = kB(memMap.get("MemAvailable"));
        snap.swapTotal = kB(memMap.get("SwapTotal"));
        snap.swapFree = kB(memMap.get("SwapFree"));
        snap.disks.addAll(parseDisks(sections.getOrDefault("DISK", List.of())));
        snap.ports.addAll(parsePorts(sections.getOrDefault("PORTS", List.of())));
        snap.procs.addAll(parseProcs(sections.getOrDefault("PROC", List.of())));
        if (snap.memTotal < 0 && snap.disks.isEmpty() && snap.ports.isEmpty()) {
            snap.error = "未能解析监控数据";
        }
        return snap;
    }

    private static long kB(Long v) {
        return v == null ? -1 : v * 1024;
    }

    static Map<String, List<String>> sectionize(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String cur = null;
        for (String line : raw.split("\r?\n")) {
            String t = line.strip();
            if (t.matches("@@[A-Z]+")) {
                cur = t.substring(2);
                out.put(cur, new ArrayList<>());
                continue;
            }
            if (cur != null) out.get(cur).add(t);
        }
        return out;
    }

    /** 由两帧 /proc/stat 的 cpu 行计算 CPU 使用率（百分比）。 */
    public static double cpuPercent(String prev, String cur) {
        long[] a = cpuFields(prev);
        long[] b = cpuFields(cur);
        if (a == null || b == null) return -1;
        long ta = 0, tb = 0;
        for (int i = 0; i < 8; i++) {
            ta += a[i];
            tb += b[i];
        }
        long idleA = a[3] + a[4], idleB = b[3] + b[4];
        long dTotal = tb - ta, dIdle = idleB - idleA;
        if (dTotal <= 0) return -1;
        double p = (dTotal - dIdle) * 100.0 / dTotal;
        return Math.max(0, Math.min(100, p));
    }

    private static long[] cpuFields(String line) {
        if (line == null) return null;
        String t = line.strip();
        if (!t.startsWith("cpu")) return null;
        String[] f = t.split("\\s+");
        long[] v = new long[8];
        try {
            for (int i = 0; i < 8; i++) {
                v[i] = Long.parseLong(f[i + 1]);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return v;
    }

    public static List<MonitorSnapshot.Disk> parseDisks(List<String> lines) {
        List<MonitorSnapshot.Disk> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("Filesystem")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 6) continue;
            String fs = f[0];
            String mount = f[5];
            if (fs.contains(":")) continue;
            boolean skip = false;
            for (String s : DF_SKIP_FS) {
                if (fs.equals(s) || fs.startsWith(s)) {
                    skip = true;
                    break;
                }
            }
            if (skip || mount.startsWith("/sys") || mount.startsWith("/proc") || mount.startsWith("/dev")) continue;
            try {
                MonitorSnapshot.Disk d = new MonitorSnapshot.Disk();
                d.fs = fs;
                d.mount = mount;
                d.total = Long.parseLong(f[1]) * 1024;
                d.used = Long.parseLong(f[2]) * 1024;
                out.add(d);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<MonitorSnapshot.PortListen> parsePorts(List<String> lines) {
        List<MonitorSnapshot.PortListen> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("Netid")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 6) continue;
            String proto = f[0];
            String local = f[4];
            int ci = local.lastIndexOf(':');
            if (ci < 0) continue;
            MonitorSnapshot.PortListen p = new MonitorSnapshot.PortListen();
            p.proto = proto;
            p.addr = local.substring(0, ci);
            try {
                p.port = Integer.parseInt(local.substring(ci + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\(\\(\"([^\"]+)\",pid=(\\d+)")
                    .matcher(t);
            if (m.find()) {
                p.process = m.group(1);
                p.pid = Integer.parseInt(m.group(2));
            }
            out.add(p);
        }
        return out;
    }

    public static List<MonitorSnapshot.Proc> parseProcs(List<String> lines) {
        List<MonitorSnapshot.Proc> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("PID ")) continue;
            String[] f = t.split("\\s+", 7);
            if (f.length < 7) continue;
            try {
                MonitorSnapshot.Proc p = new MonitorSnapshot.Proc();
                p.pid = Long.parseLong(f[0]);
                p.user = f[1];
                p.cpu = Double.parseDouble(f[2]);
                p.mem = Double.parseDouble(f[3]);
                p.rssKb = Long.parseLong(f[4]);
                p.elapsed = f[5];
                p.command = f[6];
                out.add(p);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
