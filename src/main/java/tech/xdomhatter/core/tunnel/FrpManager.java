package tech.xdomhatter.core.tunnel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import tech.xdomhatter.core.model.AppConfig;
import tech.xdomhatter.core.model.FrpProxy;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.ssh.SshManager;
import tech.xdomhatter.core.store.AppPaths;
import tech.xdomhatter.core.store.CredentialVault;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * frp 管理：本机 frpc 进程启停 + 配置生成 + 日志；
 * 服务器端 frps 通过 SSH/systemd 管理，支持一键部署。
 */
public class FrpManager {
    private final SshManager ssh;
    private final CredentialVault vault;
    private final Map<String, FrpcProc> running = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private static final class FrpcProc {
        final Process process;
        final ArrayDeque<String> log = new ArrayDeque<>();

        FrpcProc(Process process) {
            this.process = process;
        }

        synchronized void append(String line) {
            log.addLast(line);
            while (log.size() > 500) log.removeFirst();
        }

        synchronized List<String> tail(int n) {
            List<String> out = new ArrayList<>(log);
            return out.size() <= n ? out : out.subList(out.size() - n, out.size());
        }
    }

    public FrpManager(SshManager ssh, CredentialVault vault) {
        this.ssh = ssh;
        this.vault = vault;
    }

    // ---------- 本机 frpc ----------

    public boolean frpcRunning(String profileId) {
        FrpcProc p = running.get(profileId);
        return p != null && p.process.isAlive();
    }

    public List<String> frpcLog(String profileId, int lines) {
        FrpcProc p = running.get(profileId);
        return p == null ? List.of("(frpc 未在运行)") : p.tail(lines);
    }

    public void startFrpc(String profileId, SshProfile profile, List<FrpProxy> proxies, AppConfig.Settings st)
            throws IOException {
        FrpcProc old = running.get(profileId);
        if (old != null && old.process.isAlive()) throw new IllegalStateException("frpc 已在运行");
        List<FrpProxy> enabled = proxies.stream().filter(p -> p.enabled).toList();
        if (enabled.isEmpty()) throw new IllegalStateException("没有已启用的 frp 代理");
        String token = vaultSecret();
        String bin = st.frpcPath == null || st.frpcPath.isBlank() ? AppPaths.frpcDefault() : st.frpcPath;
        Path conf = AppPaths.FRP_CONF.resolve("frpc-" + profileId + ".toml");
        Files.writeString(conf, frpcToml(profile.host, st.frpsBindPort, token, enabled,
                st.frpcAdminEnabled ? st.frpcAdminPort : 0), StandardCharsets.UTF_8);
        Process p = new ProcessBuilder(bin, "-c", conf.toString()).redirectErrorStream(true).start();
        FrpcProc proc = new FrpcProc(p);
        running.put(profileId, proc);
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) proc.append(line);
            } catch (IOException ignored) {
            }
        }, "frpc-log-" + profileId);
        reader.setDaemon(true);
        reader.start();
    }

    public void stopFrpc(String profileId) {
        FrpcProc p = running.get(profileId);
        if (p == null) return;
        p.process.descendants().forEach(ProcessHandle::destroyForcibly);
        p.process.destroy();
        try {
            if (!p.process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running.remove(profileId);
    }

    public void stopAllFrpc() {
        for (String id : List.copyOf(running.keySet())) stopFrpc(id);
    }

    /** 通过 frpc admin API 查询代理状态，返回 [name, status] 列表；admin 未开启时返回空。 */
    public List<String[]> frpcProxyStatus(String profileId, int adminPort) {
        FrpcProc p = running.get(profileId);
        if (p == null || !p.process.isAlive() || adminPort <= 0) return List.of();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adminPort + "/api/status"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();
            JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray arr = obj.getAsJsonArray("status");
            List<String[]> out = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                out.add(new String[]{
                        o.has("name") ? o.get("name").getAsString() : "?",
                        o.has("status") ? o.get("status").getAsString() : "?"});
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---------- 服务器端 frps ----------

    public record FrpsStatus(boolean binaryExists, String systemdState, String detail, boolean unitExists) {}

    public FrpsStatus remoteStatus(String profileId, AppConfig.Settings st) throws Exception {
        Session s = ssh.session(profileId);
        SshManager.ExecResult bin = SshManager.exec(s,
                "test -x " + q(st.frpsRemotePath) + " && echo yes || echo no", 8000);
        SshManager.ExecResult svc = SshManager.exec(s,
                "systemctl is-active " + shq(st.frpsUnitName) + " 2>&1; true", 8000);
        SshManager.ExecResult unit = SshManager.exec(s,
                "systemctl cat " + shq(st.frpsUnitName) + " >/dev/null 2>&1; echo $?", 8000);
        return new FrpsStatus(bin.stdout().strip().equals("yes"), svc.stdout().strip(), "",
                unit.stdout().strip().equals("0"));
    }

    public String remoteAction(String profileId, AppConfig.Settings st, String action) throws Exception {
        Session s = ssh.session(profileId);
        SshManager.ExecResult r = SshManager.exec(s,
                "systemctl " + action + " " + shq(st.frpsUnitName) + " 2>&1; systemctl is-active "
                        + shq(st.frpsUnitName) + " 2>&1; true", 20000);
        return r.stdout().strip();
    }

    public List<String> remoteLog(String profileId, AppConfig.Settings st, int lines) throws Exception {
        Session s = ssh.session(profileId);
        SshManager.ExecResult r = SshManager.exec(s,
                "journalctl -u " + shq(st.frpsUnitName) + " -n " + lines + " --no-pager -o cat 2>&1; true", 20000);
        return r.stdout().lines().toList();
    }

    // ---------- 自动检测与 systemd 配置 ----------

    /** 自动检测结果：二进制/配置/运行进程/已有 systemd 单元/配置里的 bindPort，未找到的项为 null。 */
    public record FrpsDetect(String binaryPath, String configPath, Integer runningPid,
                             String unitName, Integer bindPort) {}

    public record SystemdSetupResult(boolean created, boolean started, String message) {}

    record ProcProbe(Integer pid, String exe, List<String> args) {}

    /**
     * SSH 探测服务器上的 frps：优先从运行中的进程拿真实路径和 -c 参数，
     * 其次 command -v、常见路径和有限深度的 find；单元名取 systemctl list-unit-files。
     */
    public FrpsDetect detectFrps(String profileId) throws Exception {
        Session s = ssh.session(profileId);

        SshManager.ExecResult proc = SshManager.exec(s, "pid=$(pgrep -x frps | head -n1); echo \"$pid\"; "
                + "if [ -n \"$pid\" ]; then echo \"EXE=$(readlink -f /proc/$pid/exe 2>/dev/null)\"; "
                + "echo ARGS_BEGIN; tr '\\0' '\\n' < /proc/$pid/cmdline 2>/dev/null; echo ARGS_END; fi", 8000);
        ProcProbe probe = parseProcProbe(proc.stdout());

        String bin = probe.exe();
        if (bin == null) {
            SshManager.ExecResult bins = SshManager.exec(s,
                    "{ command -v frps 2>/dev/null; "
                    + "for p in /usr/local/bin/frps /usr/bin/frps /usr/local/frp/frps /opt/frp/frps /root/frp/frps \"$HOME/frp/frps\" \"$HOME/frps\"; "
                    + "do [ -x \"$p\" ] && echo \"$p\"; done; "
                    + "find /usr/local /opt /root /home /srv -maxdepth 3 -type f -name 'frps*' "
                    + "-not -name '*.toml' -not -name '*.ini' -not -name '*.yaml' -not -name '*.log' -perm -u+x 2>/dev/null | head -n5; } "
                    + "| awk 'NF && !seen[$0]++'", 15000);
            bin = pickFirst(bins.stdout());
        }

        SshManager.ExecResult units = SshManager.exec(s,
                "systemctl list-unit-files --type=service --no-legend 2>/dev/null | awk '{print $1}'", 8000);
        String unit = pickUnit(units.stdout());

        String conf = configFromArgs(probe.args());
        Integer port = null;
        if (conf != null) {
            port = parseBindPort(SshManager.exec(s, "head -c 8192 " + q(conf) + " 2>/dev/null", 8000).stdout());
        } else if (bin != null) {
            List<String> cands = new ArrayList<>(List.of("/etc/frp/frps.toml", "/etc/frp/frps.ini",
                    "/etc/frp/frps.yaml", "/etc/frp/frps.conf"));
            int slash = bin.lastIndexOf('/');
            if (slash > 0) {
                String dir = bin.substring(0, slash);
                for (String ext : List.of("toml", "ini", "yaml", "conf")) cands.add(dir + "/frps." + ext);
            }
            StringBuilder pb = new StringBuilder();
            for (String cand : cands) pb.append("[ -f ").append(q(cand)).append(" ] && echo ").append(q(cand)).append("; ");
            String found = pickFirst(SshManager.exec(s, pb.toString(), 8000).stdout());
            if (!found.isBlank()) {
                conf = found;
                port = parseBindPort(SshManager.exec(s, "head -c 8192 " + q(conf) + " 2>/dev/null", 8000).stdout());
            }
        }
        return new FrpsDetect(bin == null || bin.isBlank() ? null : bin, conf, probe.pid(), unit, port);
    }

    /**
     * 单元缺失时自动生成 frps 的 systemd unit、上传并 enable；frps 未运行时顺带 start。
     * 单元已存在则不做修改（与一键部署的强制覆盖不同）。
     */
    public SystemdSetupResult setupSystemdUnit(String profileId, AppConfig.Settings st,
                                               String binPath, String confPath) throws Exception {
        if (binPath == null || binPath.isBlank())
            throw new IllegalStateException("未提供 frps 二进制路径，请先执行“自动检测 frps”或“一键部署 frps…”");
        String unit = st.frpsUnitName == null || st.frpsUnitName.isBlank() ? "frps" : st.frpsUnitName.strip();
        if (!unit.matches("[A-Za-z0-9@._-]+")) throw new IllegalStateException("frps systemd 单元名含非法字符: " + unit);
        String unitFileName = unit.endsWith(".service") ? unit : unit + ".service";
        Session s = ssh.session(profileId);

        SshManager.ExecResult exists = SshManager.exec(s,
                "systemctl cat " + shq(unit) + " >/dev/null 2>&1; echo $?", 8000);
        if (exists.stdout().strip().equals("0")) {
            return new SystemdSetupResult(false, false, "单元 " + unitFileName + " 已存在，未做修改。");
        }

        Path tmp = AppPaths.FRP_CONF.resolve("frps-setup-" + profileId + ".service");
        Files.writeString(tmp, unitFile(binPath, confPath), StandardCharsets.UTF_8);
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect();
        try {
            c.put(tmp.toString(), "/etc/systemd/system/" + unitFileName);
        } finally {
            c.disconnect();
        }
        SshManager.ExecResult r = SshManager.exec(s,
                "systemctl daemon-reload 2>&1 && systemctl enable " + shq(unit) + " 2>&1; true", 20000);
        StringBuilder msg = new StringBuilder();
        msg.append("已写入 /etc/systemd/system/").append(unitFileName).append("\n");
        msg.append("ExecStart=").append(binPath);
        if (confPath != null && !confPath.isBlank()) msg.append(" -c ").append(confPath);
        msg.append("\n\n").append(r.stdout().strip());
        if (!r.stderr().isBlank()) msg.append("\n").append(r.stderr().strip());

        boolean started = false;
        String running = SshManager.exec(s, "pgrep -x frps >/dev/null 2>&1 && echo yes || echo no", 8000).stdout().strip();
        if (running.equals("yes")) {
            msg.append("\n\nfrps 正在运行（非 systemd 启动）：为避免端口冲突，已只设置开机自启，未立即接管启动。")
               .append("建议停掉手动进程后执行 systemctl start ").append(unit).append("。");
        } else {
            SshManager.ExecResult up = SshManager.exec(s,
                    "systemctl start " + shq(unit) + " 2>&1; systemctl is-active " + shq(unit) + " 2>&1; true", 20000);
            started = up.stdout().strip().equals("active");
            msg.append("\n\nsystemctl start → ").append(up.stdout().strip());
            if (!up.stderr().isBlank()) msg.append("\n").append(up.stderr().strip());
        }
        return new SystemdSetupResult(true, started, msg.toString());
    }

    /** 一键部署：上传 frps 二进制 → 写配置 → 写 systemd unit → enable。 */
    public String deployFrps(String profileId, AppConfig.Settings st, Path localBinary) throws Exception {
        Session s = ssh.session(profileId);
        String token = vaultSecret();
        SshManager.exec(s, "mkdir -p /etc/frp /etc/systemd/system", 8000);
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect();
        try {
            String tmp = st.frpsRemotePath + ".upload";
            c.put(localBinary.toString(), tmp);
            SshManager.exec(s, "chmod +x " + q(tmp) + " && mv " + q(tmp) + " " + q(st.frpsRemotePath), 30000);
            Path confTmp = AppPaths.FRP_CONF.resolve("frps-" + profileId + ".toml");
            Files.writeString(confTmp, frpsToml(st.frpsBindPort, token), StandardCharsets.UTF_8);
            c.put(confTmp.toString(), st.frpsConfigPath);
            Path unitTmp = AppPaths.FRP_CONF.resolve("frps.service");
            Files.writeString(unitTmp, unitFile(st), StandardCharsets.UTF_8);
            c.put(unitTmp.toString(), "/etc/systemd/system/" + st.frpsUnitName + ".service");
        } finally {
            c.disconnect();
        }
        SshManager.ExecResult r = SshManager.exec(s,
                "systemctl daemon-reload && systemctl enable " + shq(st.frpsUnitName) + " 2>&1; true", 20000);
        return r.stdout().strip();
    }

    // ---------- frps 配置文件读写 ----------

    /** 读取服务器上的 frps 配置文件；文件不存在返回 ""。 */
    public String readRemoteConfig(String profileId, String path) throws Exception {
        Session s = ssh.session(profileId);
        return SshManager.exec(s, "cat " + q(path) + " 2>/dev/null; true", 8000).stdout();
    }

    /** 写入服务器上的 frps 配置文件：先保留 .bak 备份，再经临时文件 mv 原子覆盖。 */
    public void writeRemoteConfig(String profileId, String path, String content) throws Exception {
        if (path == null || path.isBlank()) throw new IOException("frps 配置路径未设置");
        Session s = ssh.session(profileId);
        SshManager.exec(s, "[ -f " + q(path) + " ] && cp -f " + q(path) + " " + q(path + ".bak") + " 2>/dev/null; true", 8000);
        Path tmp = AppPaths.FRP_CONF.resolve("frps-edit-" + profileId + ".toml");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect();
        try {
            String remoteTmp = path + ".justserving-tmp";
            c.put(tmp.toString(), remoteTmp);
            SshManager.ExecResult mv = SshManager.exec(s, "mv -f " + q(remoteTmp) + " " + q(path) + " 2>&1", 8000);
            if (mv.exitCode() != 0)
                throw new IOException("写入 frps 配置失败: " + (mv.stdout() + mv.stderr()).strip());
        } finally {
            c.disconnect();
        }
    }

    /** frps 配置表单编辑：字段值为 null 表示未修改，"" 表示删除该键；端口为十进制字符串。 */
    public record FrpsEdits(String bindPort, String authToken, String vhostHttpPort, String vhostHttpsPort,
                            String subdomainHost, String dashboardAddr, String dashboardPort,
                            String dashboardUser, String dashboardPassword) {}

    private static final Set<String> FRPS_STRING_KEYS = Set.of(
            "auth.token", "subdomainHost", "webServer.addr", "webServer.user", "webServer.password");

    /**
     * 将表单编辑行级合并进原始配置：只改动涉及的键，注释与未涉及字段原样保留。
     * 新增顶层键插到第一个 [section] 之前；新增小节键并入已有 [section]，没有则文件尾追加新小节。
     */
    public static String applyFrpsEdits(String original, FrpsEdits e) {
        List<String[]> changes = new ArrayList<>();
        if (e.bindPort != null) changes.add(new String[]{"bindPort", e.bindPort});
        if (e.authToken != null) changes.add(new String[]{"auth.token", e.authToken});
        if (e.vhostHttpPort != null) changes.add(new String[]{"vhostHTTPPort", e.vhostHttpPort});
        if (e.vhostHttpsPort != null) changes.add(new String[]{"vhostHTTPSPort", e.vhostHttpsPort});
        if (e.subdomainHost != null) changes.add(new String[]{"subdomainHost", e.subdomainHost});
        if (e.dashboardAddr != null) changes.add(new String[]{"webServer.addr", e.dashboardAddr});
        if (e.dashboardPort != null) changes.add(new String[]{"webServer.port", e.dashboardPort});
        if (e.dashboardUser != null) changes.add(new String[]{"webServer.user", e.dashboardUser});
        if (e.dashboardPassword != null) changes.add(new String[]{"webServer.password", e.dashboardPassword});
        List<String> lines = new ArrayList<>(List.of(original.split("\\R", -1)));
        for (String[] ch : changes) applyTomlChange(lines, ch[0], ch[1]);
        return String.join("\n", lines);
    }

    private static void applyTomlChange(List<String> lines, String dottedKey, String value) {
        int dot = dottedKey.indexOf('.');
        String section = dot < 0 ? null : dottedKey.substring(0, dot);
        String key = dot < 0 ? dottedKey : dottedKey.substring(dot + 1);
        String cur = "";
        int hit = -1;
        int sectionHeader = -1;
        int sectionEnd = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("[") && line.endsWith("]")) {
                if (sectionHeader >= 0 && sectionEnd < 0) sectionEnd = i;
                cur = line.substring(1, line.length() - 1).strip();
                if (section != null && cur.equals(section)) {
                    sectionHeader = i;
                    sectionEnd = -1;
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || hit >= 0) continue;
            String k = line.substring(0, eq).strip();
            boolean dottedTop = section != null && cur.isEmpty() && k.equals(dottedKey);
            boolean inSection = section != null && cur.equals(section) && k.equals(key);
            boolean top = section == null && cur.isEmpty() && k.equals(key);
            if (dottedTop || inSection || top) hit = i;
        }
        if (sectionHeader >= 0 && sectionEnd < 0) sectionEnd = lines.size();

        if (hit >= 0) {
            if (value.isEmpty()) {
                lines.remove(hit);
                return;
            }
            String original = lines.get(hit);
            int eq = original.indexOf('=');
            String left = original.substring(0, eq + 1);
            String right = original.substring(eq + 1);
            int ci = indexOfTomlComment(right);
            String comment = ci >= 0 ? " " + right.substring(ci).strip() : "";
            lines.set(hit, left + " " + formatTomlValue(dottedKey, value) + comment);
            return;
        }
        if (value.isEmpty()) return;

        if (section == null) {
            int firstSection = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (line.startsWith("[") && line.endsWith("]")) {
                    firstSection = i;
                    break;
                }
            }
            if (firstSection >= 0) {
                lines.add(firstSection, key + " = " + formatTomlValue(dottedKey, value));
            } else {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
                lines.add(key + " = " + formatTomlValue(dottedKey, value));
            }
        } else if (sectionHeader >= 0) {
            lines.add(sectionEnd, key + " = " + formatTomlValue(dottedKey, value));
        } else {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add("[" + section + "]");
            lines.add(key + " = " + formatTomlValue(dottedKey, value));
        }
    }

    private static String formatTomlValue(String dottedKey, String value) {
        return FRPS_STRING_KEYS.contains(dottedKey) ? "\"" + esc(value) + "\"" : value;
    }

    /** 解析 TOML 为扁平键值（如 bindPort、auth.token、webServer.port），值保留原始写法，重复键取首个。 */
    public static Map<String, String> parseTomlFields(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        String section = "";
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip();
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip();
            String val = line.substring(eq + 1).strip();
            int ci = indexOfTomlComment(val);
            if (ci >= 0) val = val.substring(0, ci).strip();
            out.putIfAbsent(section.isEmpty() ? key : section + "." + key, val);
        }
        return out;
    }

    public static String tomlUnquote(String v) {
        if (v == null) return "";
        String t = v.strip();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))))
            return t.substring(1, t.length() - 1);
        return t;
    }

    static int indexOfTomlComment(String v) {
        boolean inStr = false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '"') inStr = !inStr;
            else if (ch == '#' && !inStr) return i;
        }
        return -1;
    }

    // ---------- 配置生成 ----------

    public static String frpcToml(String serverAddr, int serverPort, String token,
                                  List<FrpProxy> proxies, int adminPort) {
        StringBuilder sb = new StringBuilder();
        sb.append("serverAddr = \"").append(esc(serverAddr)).append("\"\n");
        sb.append("serverPort = ").append(serverPort).append("\n");
        if (token != null && !token.isBlank()) sb.append("auth.token = \"").append(esc(token)).append("\"\n");
        if (adminPort > 0) {
            sb.append("webServer.addr = \"127.0.0.1\"\n");
            sb.append("webServer.port = ").append(adminPort).append("\n");
        }
        for (FrpProxy p : proxies) {
            if (!p.enabled) continue;
            sb.append("\n[[proxies]]\n");
            sb.append("name = \"").append(esc(p.name)).append("\"\n");
            sb.append("type = \"").append(esc(p.type)).append("\"\n");
            sb.append("localIP = \"").append(esc(p.localIp)).append("\"\n");
            sb.append("localPort = ").append(p.localPort).append("\n");
            sb.append("remotePort = ").append(p.remotePort).append("\n");
        }
        return sb.toString();
    }

    public static String frpsToml(int bindPort, String token) {
        StringBuilder sb = new StringBuilder("bindPort = ").append(bindPort).append("\n");
        if (token != null && !token.isBlank()) sb.append("auth.token = \"").append(esc(token)).append("\"\n");
        return sb.toString();
    }

    public static String unitFile(AppConfig.Settings st) {
        return unitFile(st.frpsRemotePath, st.frpsConfigPath);
    }

    public static String unitFile(String binPath, String confPath) {
        return "[Unit]\nDescription=frp server (managed by JustServing)\nAfter=network.target\n\n"
                + "[Service]\nType=simple\nExecStart=" + binPath
                + (confPath == null || confPath.isBlank() ? "" : " -c " + confPath)
                + "\nRestart=on-failure\n\n[Install]\nWantedBy=multi-user.target\n";
    }

    // ---------- 自动检测的纯解析逻辑（便于单元测试） ----------

    /** 取输出中第一个非空行（用于 command -v / 路径探测结果），全空返回 ""。 */
    static String pickFirst(String output) {
        if (output == null) return "";
        for (String line : output.split("\\R")) {
            String t = line.strip();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /** 从 frps 启动参数中提取 -c/--config 指定的配置路径，支持 --config=path；没有则返回 null。 */
    static String configFromArgs(List<String> args) {
        if (args == null) return null;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if (a.equals("-c") || a.equals("--config")) {
                if (i + 1 < args.size()) {
                    String v = args.get(i + 1).strip();
                    if (!v.isEmpty()) return v;
                }
            } else if (a.startsWith("--config=")) {
                String v = a.substring("--config=".length()).strip();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** 从 systemctl list-unit-files 输出选 frps 服务单元，优先精确 frps.service，排除 frpc；返回不带 .service 的名字。 */
    static String pickUnit(String listOutput) {
        if (listOutput == null) return null;
        String first = null;
        for (String line : listOutput.split("\\R")) {
            String u = line.strip();
            if (!u.endsWith(".service")) continue;
            String base = u.substring(0, u.length() - ".service".length());
            String low = base.toLowerCase();
            if (!low.contains("frps") || low.contains("frpc")) continue;
            if (low.equals("frps")) return base;
            if (first == null) first = base;
        }
        return first;
    }

    /** 从 frps 配置文本解析 bindPort（toml）/ bind_port（ini），容忍引号和行尾注释；解析不到返回 null。 */
    static Integer parseBindPort(String confText) {
        if (confText == null) return null;
        for (String line : confText.split("\\R")) {
            String t = line.strip();
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).strip().toLowerCase().replace("_", "");
            if (!key.equals("bindport")) continue;
            String val = t.substring(eq + 1).strip();
            int hash = val.indexOf('#');
            if (hash >= 0) val = val.substring(0, hash).strip();
            val = val.replace("\"", "");
            try {
                int port = Integer.parseInt(val);
                if (port > 0 && port < 65536) return port;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** 解析 detectFrps 的进程探测输出：pid、/proc/pid/exe、ARGS_BEGIN..ARGS_END 之间的启动参数。 */
    static ProcProbe parseProcProbe(String out) {
        if (out == null || out.isBlank()) return new ProcProbe(null, null, List.of());
        Integer pid = null;
        String exe = null;
        List<String> args = List.of();
        List<String> collecting = null;
        for (String line : out.split("\\R")) {
            String t = line.strip();
            if (t.equals("ARGS_BEGIN")) {
                collecting = new ArrayList<>();
            } else if (t.equals("ARGS_END")) {
                if (collecting != null) args = List.copyOf(collecting);
                collecting = null;
            } else if (collecting != null) {
                if (!t.isEmpty()) collecting.add(t);
            } else if (t.startsWith("EXE=")) {
                String e = t.substring(4).strip();
                if (!e.isEmpty()) exe = e;
            } else if (pid == null && t.matches("\\d+")) {
                pid = Integer.parseInt(t);
            }
        }
        if (collecting != null && !collecting.isEmpty()) args = List.copyOf(collecting);
        return new ProcProbe(pid, exe, args);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String q(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    static String shq(String s) {
        return q(s);
    }

    private String vaultSecret() {
        try {
            return vault.getSecret("frps.token");
        } catch (Exception e) {
            throw new RuntimeException("读取 frps token 失败: " + e.getMessage(), e);
        }
    }
}
