package tech.xdomhatter.core.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.store.AppPaths;
import tech.xdomhatter.core.store.CredentialVault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SshManager {
    public record ExecResult(String stdout, String stderr, int exitCode) {}

    private final CredentialVault vault;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SshManager(CredentialVault vault) {
        this.vault = vault;
    }

    public void connect(SshProfile p) throws JSchException {
        Session old = sessions.get(p.id);
        if (old != null && old.isConnected()) return;
        if (old != null) {
            old.disconnect();
            sessions.remove(p.id);
        }
        try {
            JSch jsch = new JSch();
            try {
                jsch.setKnownHosts(AppPaths.KNOWN_HOSTS.toString());
            } catch (JSchException ignored) {
            }
            if (p.authType == SshProfile.AuthType.KEY) {
                String pass = secret(p.keyPassKey());
                if (pass == null || pass.isEmpty()) {
                    jsch.addIdentity(p.keyPath);
                } else {
                    jsch.addIdentity(p.keyPath, pass);
                }
            }
            Session s = jsch.getSession(p.user, p.host, p.port);
            if (p.authType == SshProfile.AuthType.PASSWORD) {
                s.setPassword(secret(p.secretKey()));
            }
            s.setConfig("StrictHostKeyChecking", "accept-new");
            s.setServerAliveInterval(15_000);
            s.setServerAliveCountMax(4);
            // socket 读超时：僵死/半开连接（休眠恢复、断网）30s 内抛异常，避免传输线程永久阻塞
            s.setTimeout(30_000);
            s.connect(10_000);
            sessions.put(p.id, s);
        } catch (JSchException e) {
            throw e;
        }
    }

    private String secret(String name) {
        try {
            return vault.getSecret(name);
        } catch (Exception e) {
            throw new RuntimeException("读取凭据失败，请确认保险库已解锁: " + e.getMessage(), e);
        }
    }

    public boolean isConnected(String profileId) {
        Session s = sessions.get(profileId);
        return s != null && s.isConnected();
    }

    public Session session(String profileId) throws JSchException {
        Session s = sessions.get(profileId);
        if (s == null || !s.isConnected()) throw new JSchException("未连接到服务器");
        return s;
    }

    public Session sessionQuiet(String profileId) {
        Session s = sessions.get(profileId);
        return s != null && s.isConnected() ? s : null;
    }

    public void disconnect(String profileId) {
        Session s = sessions.remove(profileId);
        if (s != null) s.disconnect();
    }

    public void disconnectAll() {
        sessions.values().forEach(Session::disconnect);
        sessions.clear();
    }

    public static ExecResult exec(Session s, String command, long timeoutMs) throws JSchException, IOException {
        ChannelExec ch = (ChannelExec) s.openChannel("exec");
        ch.setCommand(command);
        ch.setInputStream(null);
        StringBuilder so = new StringBuilder();
        StringBuilder se = new StringBuilder();
        Thread to = drain(ch.getInputStream(), so);
        Thread te = drain(ch.getErrStream(), se);
        ch.connect(10_000);
        int exit = -1;
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!ch.isClosed()) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("命令执行超时: " + command);
                }
                Thread.sleep(20);
            }
            to.join(2000);
            te.join(2000);
            exit = ch.getExitStatus();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("执行被中断", e);
        } finally {
            ch.disconnect();
        }
        return new ExecResult(so.toString(), se.toString(), exit);
    }

    private static Thread drain(InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
