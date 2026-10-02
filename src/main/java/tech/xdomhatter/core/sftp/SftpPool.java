package tech.xdomhatter.core.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import tech.xdomhatter.core.ssh.SshManager;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 按 profile 复用 SFTP channel。目录浏览等短操作不再每次重建通道——
 * 每次新建 channel 需要额外 1-2 个网络往返，高延迟链路上是“打开文件夹慢”的主因。
 * ChannelSftp 非线程安全，借出/归还保证独占；借出时健康检查，通道失效自动重建并重试一次。
 */
public class SftpPool {
    private static final int MAX_IDLE = 4;
    private static final int MAX_CONCURRENT = 4;

    @FunctionalInterface
    public interface SftpOp<T> {
        T run(ChannelSftp c) throws Exception;
    }

    private final SshManager ssh;
    private final Map<String, Deque<ChannelSftp>> idle = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> limits = new ConcurrentHashMap<>();

    public SftpPool(SshManager ssh) {
        this.ssh = ssh;
    }

    /** 借一个 channel 执行 op，用完归还；通道中途失效时换新通道重试一次。 */
    public <T> T withChannel(String profileId, SftpOp<T> op) throws Exception {
        Semaphore limit = limits.computeIfAbsent(profileId, k -> new Semaphore(MAX_CONCURRENT));
        if (!limit.tryAcquire(3, TimeUnit.SECONDS)) {
            throw new JSchException("服务器通道繁忙，请稍后重试");
        }
        try {
            ChannelSftp c = acquire(profileId);
            try {
                try {
                    return op.run(c);
                } catch (Exception first) {
                    if (healthy(c)) throw first;
                    disconnectQuietly(c);
                    ChannelSftp fresh = acquire(profileId);
                    try {
                        return op.run(fresh);
                    } finally {
                        release(profileId, fresh);
                    }
                }
            } finally {
                if (healthy(c)) release(profileId, c);
                else disconnectQuietly(c);
            }
        } finally {
            limit.release();
        }
    }

    /** 断开连接时调用：丢弃该 profile 的所有空闲通道。 */
    public void close(String profileId) {
        Deque<ChannelSftp> q = idle.remove(profileId);
        limits.remove(profileId);
        if (q == null) return;
        ChannelSftp c;
        while ((c = q.pollFirst()) != null) {
            disconnectQuietly(c);
        }
    }

    public void closeAll() {
        for (String id : idle.keySet().toArray(new String[0])) {
            close(id);
        }
    }

    private ChannelSftp acquire(String profileId) throws JSchException {
        Deque<ChannelSftp> q = idle.get(profileId);
        ChannelSftp c;
        while (q != null && (c = q.pollFirst()) != null) {
            if (healthy(c)) return c;
            disconnectQuietly(c);
        }
        Session s = ssh.session(profileId);
        ChannelSftp fresh = (ChannelSftp) s.openChannel("sftp");
        fresh.connect(10_000);
        return fresh;
    }

    private void release(String profileId, ChannelSftp c) {
        if (!healthy(c)) {
            disconnectQuietly(c);
            return;
        }
        Deque<ChannelSftp> q = idle.computeIfAbsent(profileId, k -> new ConcurrentLinkedDeque<>());
        q.addFirst(c);
        ChannelSftp extra;
        while (q.size() > MAX_IDLE && (extra = q.pollLast()) != null) {
            disconnectQuietly(extra);
        }
    }

    private static boolean healthy(ChannelSftp c) {
        return c != null && c.isConnected() && !c.isClosed();
    }

    private static void disconnectQuietly(ChannelSftp c) {
        try {
            c.disconnect();
        } catch (Exception ignored) {
        }
    }
}
