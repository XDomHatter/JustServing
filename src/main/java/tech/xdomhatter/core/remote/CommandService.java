package tech.xdomhatter.core.remote;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import tech.xdomhatter.core.model.CommandTask;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.ssh.SshManager;
import tech.xdomhatter.core.store.ConfigStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 远程命令执行引擎：保存任务与单次命令共用。
 * 两条执行通道 ——
 * - JSch：复用连接管理里的 SSH 会话，支持密码/私钥（保险库凭据）；
 * - NATIVE：调用系统 ssh 客户端（BatchMode），凭 ssh-agent / 默认密钥 / ssh config 认证，
 *   适合 JSch 不支持的新密钥格式或需要 ~/.ssh/config 的场景。
 * 执行异步进行，产出 ExecHandle 供界面轮询输出、取消；最近 100 条保留在内存历史里。
 */
public class CommandService {
    private static final int MAX_HISTORY = 100;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final SshManager ssh;
    private final ConfigStore cfg;
    private final CopyOnWriteArrayList<ExecHandle> history = new CopyOnWriteArrayList<>();

    public CommandService(SshManager ssh, ConfigStore cfg) {
        this.ssh = ssh;
        this.cfg = cfg;
    }

    public List<ExecHandle> history() {
        return List.copyOf(history);
    }

    public ExecHandle handle(long id) {
        for (ExecHandle h : history) if (h.id == id) return h;
        return null;
    }

    /** 提交执行；命令为空直接返回失败的句柄。 */
    public ExecHandle execute(SshProfile p, String command, CommandTask.Transport transport, int timeoutSec) {
        boolean nativeSsh = transport == CommandTask.Transport.NATIVE;
        ExecHandle h = new ExecHandle(p.id, p.name, command, nativeSsh);
        history.addFirst(h);
        while (history.size() > MAX_HISTORY) history.removeLast();
        if (command == null || command.isBlank()) {
            h.finish(ExecHandle.State.FAILED, -1, "命令为空");
            return h;
        }
        int timeout = Math.max(1, timeoutSec) * 1000;
        Thread t = new Thread(() -> run(h, p, command.strip(), nativeSsh, timeout), "cmd-" + h.id);
        t.setDaemon(true);
        t.start();
        return h;
    }

    private void run(ExecHandle h, SshProfile p, String command, boolean nativeSsh, int timeoutMs) {
        try {
            int exit = nativeSsh ? nativeExec(h, p, command, timeoutMs) : jschExec(h, p, command, timeoutMs);
            if (h.state == ExecHandle.State.RUNNING) {
                h.finish(h.cancelRequested ? ExecHandle.State.CANCELLED : ExecHandle.State.DONE, exit, null);
            }
        } catch (Exception e) {
            if (h.state == ExecHandle.State.RUNNING) {
                if (h.cancelRequested) {
                    h.finish(ExecHandle.State.CANCELLED, -1, null);
                } else {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    h.append(msg, true);
                    h.finish(ExecHandle.State.FAILED, -1, msg);
                }
            }
        }
    }

    // ---------- JSch 通道 ----------

    private int jschExec(ExecHandle h, SshProfile p, String command, int timeoutMs) throws Exception {
        if (!ssh.isConnected(p.id)) ssh.connect(p);
        Session s = ssh.session(p.id);
        ChannelExec ch = (ChannelExec) s.openChannel("exec");
        ch.setCommand(command);
        ch.setInputStream(null);
        h.attach(ch::disconnect);
        ch.connect(CONNECT_TIMEOUT_MS);
        Thread so = drain(ch, false, h);
        Thread se = drain(ch, true, h);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!ch.isClosed()) {
            if (h.cancelRequested) throw new IOException("已取消");
            if (System.currentTimeMillis() > deadline) {
                ch.disconnect();
                h.finish(ExecHandle.State.TIMEOUT, -1, "命令超时（" + timeoutMs / 1000 + "s）");
                return -1;
            }
            Thread.sleep(30);
        }
        if (h.cancelRequested) throw new IOException("已取消");
        so.join(2000);
        se.join(2000);
        ch.disconnect();
        return ch.getExitStatus();
    }

    private static Thread drain(ChannelExec ch, boolean stderr, ExecHandle h) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    stderr ? ch.getErrStream() : ch.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) h.append(line, stderr);
            } catch (IOException ignored) {
            }
        }, stderr ? "cmd-err" : "cmd-out");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------- 原生 ssh 通道 ----------

    private int nativeExec(ExecHandle h, SshProfile p, String command, int timeoutMs) throws Exception {
        List<String> cmd = nativeCommand(p, cfg.get().settings.sshPath);
        cmd.add(command);
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        h.attach(() -> {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroy();
        });
        Thread so = drainProcess(proc, false, h);
        Thread se = drainProcess(proc, true, h);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (proc.isAlive()) {
            if (h.cancelRequested) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                throw new IOException("已取消");
            }
            if (System.currentTimeMillis() > deadline) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                h.finish(ExecHandle.State.TIMEOUT, -1, "命令超时（" + timeoutMs / 1000 + "s）");
                return -1;
            }
            Thread.sleep(30);
        }
        if (h.cancelRequested) throw new IOException("已取消");
        so.join(2000);
        se.join(2000);
        return proc.exitValue();
    }

    private static Thread drainProcess(Process proc, boolean stderr, ExecHandle h) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    stderr ? proc.getErrorStream() : proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) h.append(line, stderr);
            } catch (IOException ignored) {
            }
        }, stderr ? "cmd-native-err" : "cmd-native-out");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 组装原生 ssh 命令行（不含远端命令本身）；BatchMode 禁用交互密码提示，避免无 TTY 时卡死。 */
    static List<String> nativeCommand(SshProfile p, String sshPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(sshPath == null || sshPath.isBlank() ? "ssh" : sshPath.strip());
        cmd.add("-p");
        cmd.add(String.valueOf(p.port));
        if (p.keyPath != null && !p.keyPath.isBlank()) {
            cmd.add("-i");
            cmd.add(p.keyPath);
        }
        cmd.add("-o");
        cmd.add("BatchMode=yes");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=accept-new");
        cmd.add("-o");
        cmd.add("ConnectTimeout=10");
        cmd.add("-o");
        cmd.add("ServerAliveInterval=15");
        cmd.add(p.user + "@" + p.host);
        return cmd;
    }
}
