package tech.xdomhatter.core.remote;

import com.jcraft.jsch.Session;
import tech.xdomhatter.core.model.ServiceInfo;
import tech.xdomhatter.core.ssh.SshManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * systemd 服务管理：列出单元（list-units + list-unit-files 合并出 active/enabled），
 * 执行 start/stop/restart/enable/disable，查看 status 与 journalctl 日志。仅支持 systemd 的 Linux。
 */
public class ServiceManager {

    public record ServiceDetail(List<String> status, List<String> log) {}

    private static final List<String> ACTIONS = List.of("start", "stop", "restart", "reload", "enable", "disable");

    private final SshManager ssh;

    public ServiceManager(SshManager ssh) {
        this.ssh = ssh;
    }

    public List<ServiceInfo> list(String profileId) throws Exception {
        Session s = ssh.session(profileId);
        SshManager.ExecResult r = SshManager.exec(s,
                "echo '@@UNITS'; systemctl list-units --type=service --all --no-legend --no-pager 2>&1; "
                        + "echo '@@FILES'; systemctl list-unit-files --type=service --no-legend --no-pager 2>&1; "
                        + "echo '@@END'", 20_000);
        List<ServiceInfo> out = parse(r.stdout());
        if (out.isEmpty()) {
            String msg = r.stdout().strip();
            if (!msg.isEmpty()) {
                throw new IllegalStateException(msg.contains("systemd")
                        ? "服务器上未检测到可用的 systemd：" + firstLine(msg)
                        : "systemctl 未返回任何服务：" + firstLine(msg));
            }
        }
        return out;
    }

    /** 执行 systemctl 动作（start/stop/restart/reload/enable/disable），返回输出（含动作后的 is-active）。 */
    public String action(String profileId, String unit, String action) throws Exception {
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("不支持的操作: " + action);
        Session s = ssh.session(profileId);
        SshManager.ExecResult r = SshManager.exec(s,
                "systemctl " + action + " " + q(unit) + " 2>&1; systemctl is-active " + q(unit) + " 2>&1; true",
                30_000);
        return r.stdout().strip();
    }

    /** 一次 exec 同时取 status 与最近日志。 */
    public ServiceDetail detail(String profileId, String unit, int logLines) throws Exception {
        Session s = ssh.session(profileId);
        SshManager.ExecResult r = SshManager.exec(s,
                "echo '@@STATUS'; systemctl status " + q(unit) + " --no-pager -l 2>&1; true; "
                        + "echo '@@LOG'; journalctl -u " + q(unit) + " -n " + Math.max(1, logLines)
                        + " --no-pager -o cat 2>&1; true", 30_000);
        Map<String, List<String>> sec = sections(r.stdout());
        return new ServiceDetail(sec.getOrDefault("STATUS", List.of()), sec.getOrDefault("LOG", List.of()));
    }

    // ---------- 解析（纯函数，便于测试） ----------

    /** 解析 @@UNITS / @@FILES 两段 systemctl 输出，合并为 ServiceInfo 列表（运行中的在前）。 */
    public static List<ServiceInfo> parse(String raw) {
        Map<String, List<String>> sec = sections(raw);
        Map<String, ServiceInfo> map = new LinkedHashMap<>();
        for (String line : sec.getOrDefault("UNITS", List.of())) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("@@")) continue;
            String[] f = t.split("\\s+", 5);
            if (f.length < 4 || !f[0].contains(".")) continue;
            ServiceInfo info = map.computeIfAbsent(f[0], k -> new ServiceInfo());
            info.unit = f[0];
            info.load = f[1];
            info.active = f[2];
            info.sub = f[3];
            info.description = f.length >= 5 ? f[4] : "";
        }
        for (String line : sec.getOrDefault("FILES", List.of())) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("@@")) continue;
            String[] f = t.split("\\s+");
            if (f.length < 2) continue;
            ServiceInfo info = map.computeIfAbsent(f[0], k -> {
                ServiceInfo i = new ServiceInfo();
                i.unit = f[0];
                i.active = "inactive";
                i.sub = "dead";
                return i;
            });
            info.enabled = f[1];
        }
        List<ServiceInfo> out = new ArrayList<>(map.values());
        out.sort((a, b) -> {
            int r = Integer.compare(a.rank(), b.rank());
            return r != 0 ? r : a.unit.compareToIgnoreCase(b.unit);
        });
        return out;
    }

    private static Map<String, List<String>> sections(String raw) {
        Map<String, List<String>> out = new HashMap<>();
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

    static String q(String unit) {
        return "'" + unit.replace("'", "'\\''") + "'";
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 160 ? l.substring(0, 160) + "…" : l;
    }
}
