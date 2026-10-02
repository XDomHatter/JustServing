package tech.xdomhatter.core.tunnel;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import tech.xdomhatter.core.model.TunnelSpec;
import tech.xdomhatter.core.ssh.SshManager;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSH 端口转发管理：-L 本地转发、-R 远程转发（内网穿透主力）。
 * 后台周期探测转发端口存活状态。
 */
public class SshTunnelManager {
    public enum Status { PROBING, ACTIVE, DOWN }

    public interface Listener {
        void tunnelsChanged();
    }

    public static class Active {
        public final TunnelSpec spec;
        public volatile Status status = Status.PROBING;
        public volatile String detail = "";
        volatile ScheduledFuture<?> probeFuture;

        Active(TunnelSpec spec) {
            this.spec = spec;
        }

        public String statusText() {
            return switch (status) {
                case PROBING -> "检测中";
                case ACTIVE -> "运行中";
                case DOWN -> "不通";
            };
        }
    }

    private final SshManager ssh;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Active> running = new ConcurrentHashMap<>();
    private final ScheduledExecutorService prober = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tunnel-probe");
        t.setDaemon(true);
        return t;
    });

    public SshTunnelManager(SshManager ssh) {
        this.ssh = ssh;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public Collection<Active> all() {
        return running.values();
    }

    public Active get(String tunnelId) {
        return running.get(tunnelId);
    }

    public boolean isRunning(String tunnelId) {
        return running.containsKey(tunnelId);
    }

    public void start(TunnelSpec t) throws JSchException {
        if (running.containsKey(t.id)) return;
        Session s = ssh.session(t.profileId);
        if (t.type == TunnelSpec.Type.LOCAL) {
            s.setPortForwardingL(t.bindAddr, t.listenPort, t.targetHost, t.targetPort);
        } else {
            s.setPortForwardingR(t.bindAddr, t.listenPort, t.targetHost, t.targetPort);
        }
        Active a = new Active(t);
        running.put(t.id, a);
        a.probeFuture = prober.scheduleAtFixedRate(() -> probe(a), 1, 4, TimeUnit.SECONDS);
        notifyChanged();
    }

    public void stop(String tunnelId) {
        Active a = running.remove(tunnelId);
        if (a == null) return;
        if (a.probeFuture != null) a.probeFuture.cancel(false);
        try {
            Session s = ssh.sessionQuiet(a.spec.profileId);
            if (s != null) {
                if (a.spec.type == TunnelSpec.Type.LOCAL) {
                    s.delPortForwardingL(a.spec.bindAddr, a.spec.listenPort);
                } else {
                    s.delPortForwardingR(a.spec.bindAddr, a.spec.listenPort);
                }
            }
        } catch (Exception ignored) {
        }
        notifyChanged();
    }

    public void stopProfile(String profileId) {
        for (Active a : List.copyOf(running.values())) {
            if (a.spec.profileId.equals(profileId)) stop(a.spec.id);
        }
    }

    public void stopAll() {
        for (String id : List.copyOf(running.keySet())) stop(id);
    }

    private void probe(Active a) {
        boolean ok;
        try {
            if (a.spec.type == TunnelSpec.Type.LOCAL) {
                String host = a.spec.bindAddr == null || a.spec.bindAddr.isEmpty()
                        || a.spec.bindAddr.equals("0.0.0.0") || a.spec.bindAddr.equals("*")
                        ? "127.0.0.1" : a.spec.bindAddr;
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(host, a.spec.listenPort), 1500);
                    ok = sock.isConnected();
                }
            } else {
                Session s = ssh.sessionQuiet(a.spec.profileId);
                if (s == null) {
                    ok = false;
                } else {
                    SshManager.ExecResult r = SshManager.exec(s,
                            "ss -tlnH 2>/dev/null | grep -F ':" + a.spec.listenPort + " ' | head -n 1", 6000);
                    ok = r.exitCode() == 0 && !r.stdout().isBlank();
                }
            }
            a.status = ok ? Status.ACTIVE : Status.DOWN;
            a.detail = ok ? ""
                    : (a.spec.type == TunnelSpec.Type.REMOTE
                    ? "服务器端口未监听（检查端口占用或 sshd GatewayPorts）"
                    : "本地端口未监听");
        } catch (Exception e) {
            a.status = Status.DOWN;
            a.detail = e.getMessage() == null ? "探测失败" : e.getMessage();
        }
        notifyChanged();
    }

    private void notifyChanged() {
        for (Listener l : listeners) {
            try {
                l.tunnelsChanged();
            } catch (Exception ignored) {
            }
        }
    }
}
