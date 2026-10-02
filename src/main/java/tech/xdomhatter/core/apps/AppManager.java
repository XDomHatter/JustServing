package tech.xdomhatter.core.apps;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import tech.xdomhatter.core.model.ManagedApp;
import tech.xdomhatter.core.sftp.SftpOps;
import tech.xdomhatter.core.sftp.TransferService;
import tech.xdomhatter.core.ssh.SshManager;
import tech.xdomhatter.core.store.CredentialVault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 企业服务管理：面向服务器端应用（非系统服务）的部署、目录维护、一键启停与下载。
 * 应用注册表保存在各服务器上（~/.justserving/apps.json）；启停支持两种机制：
 * 后台进程（setsid + PID 文件，无需 root）与 systemd 单元（需 root，支持开机自启）。
 */
public class AppManager {
    public record AppStatus(String state, String pid, String uptime) {
        public boolean running() {
            return "RUNNING".equals(state);
        }

        public String text() {
            String up = uptime == null || uptime.isBlank() ? "" : " " + uptime;
            return switch (state == null ? "" : state) {
                case "RUNNING" -> "● 运行中" + up;
                case "FAILED" -> "✗ 失败";
                case "STARTING" -> "○ 启动中";
                default -> "○ 已停止";
            };
        }
    }

    public record Snapshot(List<ManagedApp> apps, Map<String, AppStatus> statuses) {}

    public record LogTail(List<String> out, List<String> err) {}

    public record DownloadPlan(TransferService.Task appTask, List<TransferService.Task> extraTasks, List<String> skipped) {}

    private static final long SHORT_TIMEOUT = 20_000;
    private static final long FILE_TIMEOUT = 60_000;
    private static final long GIT_TIMEOUT = 600_000;

    private final SshManager ssh;
    private final CredentialVault vault;
    private final TransferService transfers;
    private final RemoteAppStore store = new RemoteAppStore();

    public AppManager(SshManager ssh, CredentialVault vault, TransferService transfers) {
        this.ssh = ssh;
        this.vault = vault;
        this.transfers = transfers;
    }

    // ---------- 注册表 ----------

    /** 应用列表 + 各应用运行状态（一次 exec 批量查询）。 */
    public Snapshot snapshot(String profileId) throws Exception {
        Session s = ssh.session(profileId);
        RemoteAppStore.State st = store.load(s);
        if (st.apps.isEmpty()) return new Snapshot(st.apps, Map.of());
        String home = remoteHome(s);
        SshManager.ExecResult r = SshManager.exec(s, statusBatchCommand(st.apps, home), SHORT_TIMEOUT);
        return new Snapshot(st.apps, parseStatusBatch(r.stdout()));
    }

    /** 新增或更新应用：展开 ~、补默认日志路径、校验后写入服务器端注册表。 */
    public ManagedApp saveApp(String profileId, ManagedApp app) throws Exception {
        Session s = ssh.session(profileId);
        if (app.name == null || app.name.isBlank()) throw new IllegalArgumentException("应用名称不能为空");
        if (app.startCommand == null || app.startCommand.isBlank()) throw new IllegalArgumentException("启动命令不能为空");
        app.deployDir = expandHome(app.deployDir, remoteHome(s));
        if (!app.deployDir.startsWith("/")) throw new IllegalArgumentException("部署目录必须是绝对路径（支持 ~ 前缀，保存时自动展开）");
        resolvePaths(s, app);
        if (app.runMode == ManagedApp.RunMode.SYSTEMD) {
            RemoteAppStore.State cur = store.load(s);
            for (ManagedApp other : cur.apps) {
                if (!other.id.equals(app.id) && other.runMode == ManagedApp.RunMode.SYSTEMD
                        && unitName(other).equals(unitName(app))) {
                    throw new IllegalArgumentException("应用名称与其他 systemd 应用重名（单元名 " + unitName(app) + "）");
                }
            }
        }
        RemoteAppStore.State st = store.load(s);
        String now = LocalDateTime.now().withNano(0).toString();
        app.updatedAt = now;
        if (app.createdAt == null || app.createdAt.isBlank()) app.createdAt = now;
        ManagedApp norm = RemoteAppStore.normalize(app);
        boolean replaced = false;
        for (int i = 0; i < st.apps.size(); i++) {
            if (st.apps.get(i).id.equals(app.id)) {
                st.apps.set(i, norm);
                replaced = true;
                break;
            }
        }
        if (!replaced) st.apps.add(norm);
        store.save(s, st);
        return norm;
    }

    /** 删除应用：停用并清理 systemd 单元（如有），可选删除部署目录（额外数据目录永不删除）。 */
    public void deleteApp(String profileId, String appId, boolean removeDeployDir) throws Exception {
        Session s = ssh.session(profileId);
        RemoteAppStore.State st = store.load(s);
        ManagedApp app = findById(st.apps, appId);
        if (app == null) return;
        if (app.runMode == ManagedApp.RunMode.SYSTEMD) {
            String unit = unitName(app);
            SshManager.exec(s, "systemctl stop " + q(unit) + " 2>/dev/null; systemctl disable " + q(unit)
                    + " 2>/dev/null; rm -f /etc/systemd/system/" + q(unit) + ".service; systemctl daemon-reload 2>/dev/null; true",
                    SHORT_TIMEOUT);
        } else {
            SshManager.exec(s, stopShellCommand(app, remoteHome(s)), SHORT_TIMEOUT);
        }
        if (removeDeployDir && !app.deployDir.isBlank()) {
            SshManager.exec(s, "rm -rf " + q(app.deployDir), FILE_TIMEOUT);
        }
        st.apps.remove(app);
        store.save(s, st);
    }

    // ---------- 部署 ----------

    /**
     * 从 git 仓库部署：目录已有仓库则 ff-only 拉取，否则克隆（--depth 1）。
     * token 认证时令牌只注入命令行，不写入服务器上的 .git/config（克隆后会还原 remote URL）。
     */
    public String deployGit(String profileId, ManagedApp app, boolean cleanBefore) throws Exception {
        Session s = ssh.session(profileId);
        SshManager.ExecResult gv = SshManager.exec(s, "git --version 2>&1 || echo '@@NOGIT'", 15_000);
        if (gv.stdout().contains("@@NOGIT")) throw new IllegalStateException("服务器未安装 git，无法从仓库部署");
        String url = app.gitUrl == null ? "" : app.gitUrl.strip();
        if (url.isEmpty()) throw new IllegalArgumentException("Git 仓库地址不能为空");
        String authUrl = url;
        if ("token".equals(app.gitAuth)) {
            String token = vault.getSecret(gitTokenKey(app.id));
            if (token == null || token.isBlank())
                throw new IllegalStateException("保险库中未找到 Git 令牌（" + gitTokenKey(app.id) + "），请在部署对话框中填写");
            authUrl = injectToken(url, token);
        }
        String dir = app.deployDir;
        String branch = app.gitBranch == null ? "" : app.gitBranch.strip();
        String probe = "if [ -d " + q(dir) + "/.git ]; then echo '@@PULL'; "
                + "elif [ -d " + q(dir) + " ] && [ -n \"$(ls -A " + q(dir) + " 2>/dev/null)\" ]; then echo '@@NONEMPTY'; "
                + "else echo '@@CLONE'; fi";
        SshManager.ExecResult pr = SshManager.exec(s, probe, 15_000);
        String mode = pr.stdout().strip();
        SshManager.ExecResult r;
        if (mode.contains("@@PULL")) {
            String cmd = "GIT_TERMINAL_PROMPT=0 git -C " + q(dir) + " pull --ff-only ";
            if ("token".equals(app.gitAuth)) cmd += q(authUrl) + (branch.isEmpty() ? "" : " " + q(branch));
            else cmd += branch.isEmpty() ? "" : " origin " + q(branch);
            cmd += " 2>&1; true";
            r = SshManager.exec(s, cmd, GIT_TIMEOUT);
        } else {
            if (mode.contains("@@NONEMPTY")) {
                if (!cleanBefore) throw new IllegalStateException(
                        "部署目录已存在且不是 git 仓库：" + dir + "；可勾选“部署前清空目录”后重试");
                SshManager.exec(s, "rm -rf " + q(dir), FILE_TIMEOUT);
            }
            String cmd = "GIT_TERMINAL_PROMPT=0 git clone --depth 1";
            if (!branch.isEmpty()) cmd += " -b " + q(branch);
            cmd += " " + q(authUrl) + " " + q(dir) + " 2>&1";
            if (!authUrl.equals(url)) cmd += " && git -C " + q(dir) + " remote set-url origin " + q(url);
            cmd += "; true";
            r = SshManager.exec(s, cmd, GIT_TIMEOUT);
        }
        if (r.exitCode() != 0) throw new IllegalStateException("git 部署失败:\n" + lastLines(r.stdout() + r.stderr(), 15));
        return lastLines(r.stdout(), 10);
    }

    /**
     * 上传部署：zip 先传到临时目录再远端解压（unzip，缺失时退回 python3），完成后删除 zip；
     * 普通文件/文件夹直接经传输队列递归上传到部署目录（进度见“传输”页）。
     */
    public String deployUpload(String profileId, ManagedApp app, Path local, boolean cleanBefore) throws Exception {
        Session s = ssh.session(profileId);
        if (local == null || !Files.exists(local)) throw new IllegalArgumentException("本地部署文件不存在");
        String pre = "mkdir -p " + q(app.deployDir);
        if (cleanBefore) pre += " && find " + q(app.deployDir) + " -mindepth 1 -maxdepth 1 -exec rm -rf {} +";
        SshManager.exec(s, pre, FILE_TIMEOUT);
        boolean zip = local.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
        if (Files.isDirectory(local)) {
            // 文件夹部署：把所选文件夹的内容合并进部署目录（deployDir 即应用本体目录）
            List<TransferService.Task> tasks = new ArrayList<>();
            try (java.util.stream.Stream<Path> st = Files.list(local)) {
                for (Path child : st.sorted().toList()) {
                    tasks.add(transfers.upload(profileId, child, app.deployDir, n -> TransferService.Conflict.OVERWRITE));
                }
            }
            for (TransferService.Task t : tasks) waitTask(t);
            return "已上传到 " + app.deployDir;
        }
        if (!zip) {
            TransferService.Task t = transfers.upload(profileId, local, app.deployDir, n -> TransferService.Conflict.OVERWRITE);
            waitTask(t);
            return "已上传到 " + app.deployDir;
        }
        String home = remoteHome(s);
        String tmpDir = home + "/.justserving/tmp";
        SshManager.exec(s, "mkdir -p " + q(tmpDir), SHORT_TIMEOUT);
        String remoteZip = tmpDir + "/" + local.getFileName();
        TransferService.Task t = transfers.upload(profileId, local, tmpDir, n -> TransferService.Conflict.OVERWRITE);
        waitTask(t);
        String cmd = "if command -v unzip >/dev/null 2>&1; then unzip -oq " + q(remoteZip) + " -d " + q(app.deployDir)
                + " && rm -f " + q(remoteZip)
                + "; elif command -v python3 >/dev/null 2>&1; then python3 -m zipfile -e " + q(remoteZip) + " " + q(app.deployDir)
                + " && rm -f " + q(remoteZip)
                + "; else echo '@@NOEXTRACT'; fi; true";
        SshManager.ExecResult r = SshManager.exec(s, cmd, GIT_TIMEOUT);
        if (r.stdout().contains("@@NOEXTRACT"))
            throw new IllegalStateException("服务器缺少 unzip 与 python3，无法解压 zip；请安装 unzip 后重试");
        if (r.exitCode() != 0) throw new IllegalStateException("解压失败:\n" + lastLines(r.stdout() + r.stderr(), 15));
        return "已解压到 " + app.deployDir;
    }

    private void waitTask(TransferService.Task t) throws Exception {
        while (true) {
            TransferService.State st = t.state;
            if (st == TransferService.State.DONE) return;
            if (st == TransferService.State.FAILED) throw new IOException("传输失败: " + t.error);
            if (st == TransferService.State.CANCELLED) throw new IOException("传输已取消");
            Thread.sleep(200);
        }
    }

    // ---------- 启停 ----------

    /** 一键启动：后台进程模式带“已在运行”守卫；systemd 模式先确保单元文件最新。 */
    public AppStatus start(String profileId, ManagedApp app, String sshUser) throws Exception {
        Session s = ssh.session(profileId);
        if (app.runMode == ManagedApp.RunMode.SYSTEMD) {
            ensureUnit(s, app, sshUser);
            SshManager.ExecResult r = SshManager.exec(s, "systemctl start " + q(unitName(app))
                    + " 2>&1; systemctl is-active " + q(unitName(app)) + " 2>&1; true", 30_000);
            if (!r.stdout().strip().equals("active"))
                throw new IllegalStateException("启动失败: " + lastLines(r.stdout() + r.stderr(), 10));
            return status(profileId, app);
        }
        SshManager.ExecResult r = SshManager.exec(s, startShellCommand(app, remoteHome(s)), 30_000);
        String out = r.stdout();
        if (out.contains("@@ALREADY")) throw new IllegalStateException("应用已在运行，无需重复启动");
        if (out.contains("@@NOSTDIN")) throw new IllegalStateException("标准输入文件不存在: " + app.stdinPath);
        if (out.contains("@@MKTDIR_FAILED")) throw new IllegalStateException("创建目录失败（检查部署目录与权限）:\n" + lastLines(r.stderr(), 8));
        if (!out.contains("@@STARTED")) throw new IllegalStateException("启动失败:\n" + lastLines(out + r.stderr(), 10));
        return status(profileId, app);
    }

    /** 一键停止：优先使用自定义停止命令；否则按 PID 文件杀进程组（先 TERM 后 KILL）。 */
    public AppStatus stop(String profileId, ManagedApp app) throws Exception {
        Session s = ssh.session(profileId);
        if (app.runMode == ManagedApp.RunMode.SYSTEMD) {
            SshManager.ExecResult r = SshManager.exec(s, "systemctl stop " + q(unitName(app))
                    + " 2>&1; systemctl is-active " + q(unitName(app)) + " 2>&1; true", 30_000);
            String st = r.stdout().strip();
            if (!st.isEmpty() && !"inactive".equals(st) && !"unknown".equals(st))
                throw new IllegalStateException("停止失败: " + lastLines(r.stdout() + r.stderr(), 10));
            return new AppStatus("STOPPED", "", "");
        }
        SshManager.exec(s, stopShellCommand(app, remoteHome(s)), 30_000);
        return new AppStatus("STOPPED", "", "");
    }

    public AppStatus restart(String profileId, ManagedApp app, String sshUser) throws Exception {
        stop(profileId, app);
        return start(profileId, app, sshUser);
    }

    public AppStatus status(String profileId, ManagedApp app) throws Exception {
        Session s = ssh.session(profileId);
        String cmd = app.runMode == ManagedApp.RunMode.SYSTEMD
                ? systemdStatusCommand(unitName(app))
                : detachedStatusCommand(pidFile(remoteHome(s), app.id));
        SshManager.ExecResult r = SshManager.exec(s, cmd, SHORT_TIMEOUT);
        return parseStatusSection(r.stdout());
    }

    /** 查看应用输出日志（stdout/stderr 最近 N 行，一次 exec）。 */
    public LogTail tail(String profileId, ManagedApp app, int lines) throws Exception {
        Session s = ssh.session(profileId);
        int n = Math.max(1, lines);
        SshManager.ExecResult r = SshManager.exec(s,
                "echo '@@OUT'; tail -n " + n + " " + q(app.stdoutPath) + " 2>&1; "
                        + "echo '@@ERR'; tail -n " + n + " " + q(app.stderrPath) + " 2>&1; true", SHORT_TIMEOUT);
        Map<String, List<String>> sec = sections(r.stdout());
        return new LogTail(sec.getOrDefault("OUT", List.of()), sec.getOrDefault("ERR", List.of()));
    }

    /** 前台调试命令：在 PTY 终端中交互运行（stdin=键盘、stdout=屏幕），关闭终端即结束应用。 */
    public static String debugCommand(ManagedApp app) {
        return "cd " + q(app.deployDir) + " && { " + envPrefix(app) + "exec " + app.startCommand + "; }";
    }

    // ---------- systemd 单元 ----------

    /** 写入/更新 systemd 单元文件（内容变化才写 + daemon-reload）。 */
    public void ensureUnit(Session s, ManagedApp app, String sshUser) throws Exception {
        String unit = unitName(app);
        String path = "/etc/systemd/system/" + unit + ".service";
        String user = sshUser == null || sshUser.isBlank() || "root".equals(sshUser) ? "" : sshUser;
        SshManager.ExecResult cur = SshManager.exec(s, "cat " + q(path) + " 2>/dev/null; true", 10_000);
        String want = unitFile(app, user);
        if (cur.stdout().equals(want)) return;
        Path tmp = Files.createTempFile("justserving-", ".service");
        try {
            Files.writeString(tmp, want, StandardCharsets.UTF_8);
            ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
            c.connect();
            try {
                c.put(tmp.toString(), path);
            } finally {
                c.disconnect();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        SshManager.exec(s, "systemctl daemon-reload", 15_000);
    }

    /** 开机自启（enable/disable）。 */
    public String setAutoStart(String profileId, ManagedApp app, boolean on) throws Exception {
        Session s = ssh.session(profileId);
        SshManager.ExecResult r = SshManager.exec(s, "systemctl " + (on ? "enable" : "disable") + " "
                + q(unitName(app)) + " 2>&1; true", 15_000);
        return r.stdout().strip();
    }

    public static String unitName(ManagedApp app) {
        String s = sanitizeUnitName(app.name);
        // 纯 CJK 等无 ASCII 字符的名称统一退化为 app-<id>，避免不同应用重名
        if (!s.matches(".*[a-z0-9].*")) s = "app-" + app.id;
        return "justserving-" + s;
    }

    public static String sanitizeUnitName(String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (char c : n.toCharArray()) {
            boolean asciiWord = c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
            sb.append(asciiWord ? c : '-');
        }
        return sb.isEmpty() ? "app" : sb.toString();
    }

    public static String unitFile(ManagedApp app, String user) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Unit]\nDescription=").append(unitName(app)).append(" — ").append(app.name).append(" (JustServing 应用)\n");
        sb.append("After=network.target\n\n[Service]\nType=simple\n");
        if (user != null && !user.isBlank() && !"root".equals(user)) sb.append("User=").append(user).append("\n");
        sb.append("WorkingDirectory=").append(app.deployDir).append("\n");
        for (String e : app.env) sb.append("Environment=").append(unitEnvValue(e)).append("\n");
        sb.append("ExecStart=/bin/bash -c ").append(unitExecArg("exec " + app.startCommand)).append("\n");
        sb.append("StandardInput=file:").append(app.stdinPath).append("\n");
        sb.append("StandardOutput=append:").append(app.stdoutPath).append("\n");
        sb.append("StandardError=append:").append(app.stderrPath).append("\n");
        sb.append("Restart=on-failure\nRestartSec=3\n\n[Install]\nWantedBy=multi-user.target\n");
        return sb.toString();
    }

    /** systemd Environment= 值：双引号包裹，转义反斜杠与双引号（$ 为字面量）。 */
    static String unitEnvValue(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** systemd ExecStart 参数：额外把 $ 转成 $$（systemd 会展开单个 $）。 */
    static String unitExecArg(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "$$") + "\"";
    }

    // ---------- 下载 ----------

    /** 应用本体 + 额外数据目录下载到本地；不存在的额外路径跳过并在 skipped 中报告。 */
    public DownloadPlan download(String profileId, ManagedApp app, Path localDir) throws Exception {
        Session s = ssh.session(profileId);
        Path root = localDir.resolve(sanitizeLocalName(app.name));
        Path appDir = root.resolve("app");
        Files.createDirectories(appDir);
        TransferService.Task appTask = transfers.download(profileId, app.deployDir, appDir,
                n -> TransferService.Conflict.OVERWRITE);
        List<TransferService.Task> extras = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (!app.extraPaths.isEmpty()) {
            StringBuilder cmd = new StringBuilder();
            for (int i = 0; i < app.extraPaths.size(); i++) {
                cmd.append("[ -e ").append(q(app.extraPaths.get(i))).append(" ] && echo '@@OK:").append(i)
                        .append("' || echo '@@MISS:").append(i).append("'; ");
            }
            cmd.append("echo '@@END'");
            SshManager.ExecResult r = SshManager.exec(s, cmd.toString(), SHORT_TIMEOUT);
            Set<Integer> present = new HashSet<>();
            for (String line : r.stdout().split("\r?\n")) {
                String t = line.strip();
                if (t.startsWith("@@OK:")) present.add(Integer.parseInt(t.substring(5)));
            }
            Set<String> used = new HashSet<>();
            for (int i = 0; i < app.extraPaths.size(); i++) {
                String p = app.extraPaths.get(i);
                if (!present.contains(i)) {
                    skipped.add(p);
                    continue;
                }
                Path target = root.resolve("extra").resolve(extraTargetName(p, used));
                Files.createDirectories(target);
                extras.add(transfers.download(profileId, p, target, n -> TransferService.Conflict.OVERWRITE));
            }
        }
        return new DownloadPlan(appTask, extras, skipped);
    }

    /** 额外数据路径的本地目标目录名（取末段，重名加 -2/-3…）。 */
    public static String extraTargetName(String remotePath, Set<String> used) {
        String p = remotePath.strip();
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        String base = SftpOps.nameOf(p);
        if (base.isBlank()) base = "data";
        String cand = base;
        for (int i = 2; used.contains(cand); i++) cand = base + "-" + i;
        return cand;
    }

    static String sanitizeLocalName(String name) {
        String n = name == null ? "" : name.strip();
        StringBuilder sb = new StringBuilder();
        for (char c : n.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ' ') sb.append(c);
            else sb.append('-');
        }
        return sb.isEmpty() ? "app" : sb.toString();
    }

    // ---------- Git 认证 ----------

    public static String gitTokenKey(String appId) {
        return "app." + appId + ".git.token";
    }

    /** 将 token 注入 http(s) 仓库 URL；ssh 等其他形式原样返回（使用服务器自身凭证）。 */
    public static String injectToken(String url, String token) {
        String u = url == null ? "" : url.strip();
        int scheme = u.indexOf("://");
        if (scheme <= 0 || token == null || token.isBlank()) return u;
        String lowerPrefix = u.substring(0, scheme).toLowerCase(Locale.ROOT);
        if (!lowerPrefix.equals("https") && !lowerPrefix.equals("http")) return u;
        String rest = u.substring(scheme + 3);
        int slash = rest.indexOf('/');
        String hostPart = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        int at = hostPart.lastIndexOf('@');
        hostPart = at < 0 ? token + "@" + hostPart : hostPart.substring(0, at) + ":" + token + hostPart.substring(at);
        return u.substring(0, scheme + 3) + hostPart + path;
    }

    // ---------- 命令构造（纯函数，便于测试） ----------

    /** 后台模式启动：setsid 使进程脱离会话（断开 SSH 仍运行），PID 文件记录进程组长。 */
    static String startShellCommand(ManagedApp app, String home) {
        String pid = pidFile(home, app.id);
        return "if [ -f " + q(pid) + " ] && kill -0 \"$(cat " + q(pid) + " 2>/dev/null)\" 2>/dev/null; then echo '@@ALREADY'; "
                + "elif [ ! -r " + q(app.stdinPath) + " ]; then echo '@@NOSTDIN'; "
                + "else if mkdir -p " + q(dirname(pid)) + " " + q(dirname(app.stdoutPath)) + " " + q(dirname(app.stderrPath))
                + " && cd " + q(app.deployDir) + "; then "
                + "setsid bash -c " + q(envPrefix(app) + "exec " + app.startCommand)
                + " < " + q(app.stdinPath) + " > " + q(app.stdoutPath) + " 2> " + q(app.stderrPath)
                + " & echo $! > " + q(pid) + "; echo '@@STARTED'; "
                + "else echo '@@MKTDIR_FAILED'; fi; fi";
    }

    static String stopShellCommand(ManagedApp app, String home) {
        if (app.stopCommand != null && !app.stopCommand.isBlank()) {
            return "cd " + q(app.deployDir) + " && bash -c " + q(app.stopCommand)
                    + "; rm -f " + q(pidFile(home, app.id)) + "; true";
        }
        String p = pidFile(home, app.id);
        return "p=" + q(p) + "; "
                + "if [ -f \"$p\" ]; then pid=$(cat \"$p\" 2>/dev/null); "
                + "if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then "
                + "kill -- -\"$pid\" 2>/dev/null || kill \"$pid\" 2>/dev/null; "
                + "i=0; while kill -0 \"$pid\" 2>/dev/null && [ \"$i\" -lt 20 ]; do sleep 0.25; i=$((i+1)); done; "
                + "if kill -0 \"$pid\" 2>/dev/null; then kill -9 -- -\"$pid\" 2>/dev/null || kill -9 \"$pid\" 2>/dev/null; fi; "
                + "echo '@@STOPPED'; else echo '@@NOT_RUNNING'; fi; rm -f \"$p\"; "
                + "else echo '@@NOT_RUNNING'; fi";
    }

    static String detachedStatusCommand(String pidFile) {
        return "p=" + q(pidFile) + "; "
                + "if [ -f \"$p\" ] && kill -0 \"$(cat \"$p\" 2>/dev/null)\" 2>/dev/null; then echo RUNNING; "
                + "echo \"PID=$(cat \"$p\")\"; ps -o etime= -p \"$(cat \"$p\")\" 2>/dev/null | tr -d ' '; "
                + "else echo STOPPED; fi";
    }

    static String systemdStatusCommand(String unit) {
        String u = q(unit);
        return "st=$(systemctl is-active " + u + " 2>&1); echo \"$st\"; "
                + "pid=$(systemctl show " + u + " -p MainPID --value 2>/dev/null); "
                + "if [ -n \"$pid\" ] && [ \"$pid\" != \"0\" ]; then ps -o etime= -p \"$pid\" 2>/dev/null | tr -d ' '; fi";
    }

    static String statusBatchCommand(List<ManagedApp> apps, String home) {
        StringBuilder sb = new StringBuilder();
        for (ManagedApp a : apps) {
            sb.append("echo '@@APP:").append(a.id).append("'; ");
            sb.append(a.runMode == ManagedApp.RunMode.SYSTEMD
                    ? systemdStatusCommand(unitName(a))
                    : detachedStatusCommand(pidFile(home, a.id)));
            sb.append("; ");
        }
        sb.append("echo '@@END'");
        return sb.toString();
    }

    /** 环境变量前缀：export 'KEY=VALUE'; …（q() 负责转义值中的引号）。 */
    static String envPrefix(ManagedApp app) {
        if (app.env == null || app.env.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String e : app.env) sb.append("export ").append(q(e)).append("; ");
        return sb.toString();
    }

    static String pidFile(String home, String appId) {
        return home + "/.justserving/pids/" + appId + ".pid";
    }

    static String dirname(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    static String expandHome(String path, String home) {
        if (path == null) return "";
        String p = path.strip();
        if (p.equals("~")) return home;
        if (p.startsWith("~/")) return home + "/" + p.substring(2);
        return p;
    }

    // ---------- 解析（纯函数，便于测试） ----------

    public static AppStatus parseStatusSection(String raw) {
        String state = "STOPPED", pid = "", uptime = "";
        List<String> lines = raw.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (!lines.isEmpty()) {
            state = normalizeState(lines.get(0));
            for (int i = 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.startsWith("PID=")) pid = l.substring(4).strip();
                else if (uptime.isEmpty()) uptime = l;
            }
        }
        return new AppStatus(state, pid, uptime);
    }

    static String normalizeState(String s) {
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "RUNNING", "RELOADING" -> "RUNNING";
            case "ACTIVATING" -> "STARTING";
            case "FAILED" -> "FAILED";
            default -> "STOPPED";
        };
    }

    public static Map<String, AppStatus> parseStatusBatch(String raw) {
        Map<String, AppStatus> out = new LinkedHashMap<>();
        String cur = null;
        StringBuilder buf = new StringBuilder();
        for (String line : raw.split("\r?\n")) {
            String t = line.strip();
            if (t.startsWith("@@APP:")) {
                if (cur != null) out.put(cur, parseStatusSection(buf.toString()));
                cur = t.substring(6);
                buf.setLength(0);
            } else if (t.equals("@@END")) {
                if (cur != null) out.put(cur, parseStatusSection(buf.toString()));
                cur = null;
            } else if (cur != null) {
                buf.append(line).append('\n');
            }
        }
        if (cur != null) out.put(cur, parseStatusSection(buf.toString()));
        return out;
    }

    // ---------- 内部 ----------

    private void resolvePaths(Session s, ManagedApp app) throws Exception {
        String home = remoteHome(s);
        app.stdinPath = expandHome(app.stdinPath, home);
        if (app.stdinPath.isBlank()) app.stdinPath = "/dev/null";
        app.stdoutPath = app.stdoutPath == null || app.stdoutPath.isBlank()
                ? home + "/.justserving/logs/" + app.id + ".out.log"
                : expandHome(app.stdoutPath, home);
        app.stderrPath = app.stderrPath == null || app.stderrPath.isBlank()
                ? home + "/.justserving/logs/" + app.id + ".err.log"
                : expandHome(app.stderrPath, home);
        if (app.extraPaths != null) {
            for (int i = 0; i < app.extraPaths.size(); i++) {
                app.extraPaths.set(i, expandHome(app.extraPaths.get(i), home));
            }
        }
    }

    private String remoteHome(Session s) throws Exception {
        SshManager.ExecResult r = SshManager.exec(s, "printf %s \"$HOME\"", 10_000);
        String h = r.stdout().strip();
        if (h.isEmpty()) throw new IllegalStateException("无法确定服务器用户主目录（$HOME 为空）");
        return h;
    }

    private static ManagedApp findById(List<ManagedApp> apps, String id) {
        for (ManagedApp a : apps) if (a.id.equals(id)) return a;
        return null;
    }

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static String lastLines(String s, int n) {
        if (s == null) return "";
        List<String> lines = s.lines().toList();
        int from = Math.max(0, lines.size() - n);
        return String.join("\n", lines.subList(from, lines.size())).strip();
    }

    private static Map<String, List<String>> sections(String raw) {
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
}
