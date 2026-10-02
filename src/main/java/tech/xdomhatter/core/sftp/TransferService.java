package tech.xdomhatter.core.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import tech.xdomhatter.core.ssh.SshManager;
import tech.xdomhatter.core.util.Fmt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class TransferService {
    public enum Direction { UPLOAD, DOWNLOAD }

    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    public enum Conflict { OVERWRITE, SKIP, RENAME }

    public interface ConflictResolver {
        Conflict resolve(String fileName);
    }

    public interface Listener {
        void transfersChanged();
    }

    private static final class CancelledException extends RuntimeException {}

    public static class Task {
        public final long id;
        public final Direction direction;
        public final String profileId;
        public final String source;
        public final String dest;
        public volatile State state = State.QUEUED;
        public volatile String error = "";
        public volatile long totalBytes = -1;
        public volatile long transferred;
        public volatile String currentFile = "";
        public volatile String speedText = "";
        volatile boolean cancel;
        volatile ChannelSftp sftp;
        volatile long lastNotify;

        Task(long id, Direction direction, String profileId, String source, String dest) {
            this.id = id;
            this.direction = direction;
            this.profileId = profileId;
            this.source = source;
            this.dest = dest;
        }

        public int percent() {
            return totalBytes <= 0 ? 0 : (int) Math.min(100, transferred * 100 / totalBytes);
        }

        public String describe() {
            String name = direction == Direction.UPLOAD
                    ? SftpOps.nameOf(source)
                    : SftpOps.nameOf(source);
            return name;
        }
    }

    private final SshManager ssh;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final ExecutorService pool;
    private final AtomicLong seq = new AtomicLong();

    public TransferService(SshManager ssh, int threads) {
        this.ssh = ssh;
        int n = Math.max(1, threads);
        pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "transfer");
            t.setDaemon(true);
            return t;
        });
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public List<Task> tasks() {
        return List.copyOf(tasks);
    }

    public Task upload(String profileId, Path local, String remoteDir, ConflictResolver resolver) {
        Task t = new Task(seq.incrementAndGet(), Direction.UPLOAD, profileId, local.toString(), remoteDir);
        tasks.add(t);
        changed(t);
        pool.submit(() -> run(t, () -> doUpload(t, local, remoteDir, resolver)));
        return t;
    }

    public Task download(String profileId, String remote, Path localDir, ConflictResolver resolver) {
        Task t = new Task(seq.incrementAndGet(), Direction.DOWNLOAD, profileId, remote, localDir.toString());
        tasks.add(t);
        changed(t);
        pool.submit(() -> run(t, () -> doDownload(t, remote, localDir, resolver)));
        return t;
    }

    public void cancel(Task t) {
        t.cancel = true;
        if (t.state == State.QUEUED) {
            t.state = State.CANCELLED;
            changed(t);
        }
    }

    public void clearFinished() {
        tasks.removeIf(t -> t.state == State.DONE || t.state == State.CANCELLED);
        notifyAllListeners();
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private void run(Task t, TaskBody body) {
        t.state = State.RUNNING;
        changed(t);
        try {
            Session s = ssh.session(t.profileId);
            ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
            c.connect();
            t.sftp = c;
            try {
                body.run();
            } finally {
                c.disconnect();
                t.sftp = null;
            }
            t.state = t.cancel ? State.CANCELLED : State.DONE;
        } catch (CancelledException e) {
            t.state = State.CANCELLED;
        } catch (Exception e) {
            if (t.cancel) {
                t.state = State.CANCELLED;
            } else {
                t.state = State.FAILED;
                t.error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } finally {
            changed(t);
        }
    }

    private interface TaskBody {
        void run() throws Exception;
    }

    // ---------- upload ----------

    private void doUpload(Task t, Path local, String remoteDir, ConflictResolver r) throws Exception {
        ChannelSftp c = t.sftp;
        if (Files.isDirectory(local)) {
            String base = SftpOps.join(remoteDir, local.getFileName().toString());
            t.totalBytes = sizeOfLocal(local);
            SftpOps.mkdirp(c, base);
            uploadDir(t, c, local, base, r);
        } else {
            t.totalBytes = Files.size(local);
            SftpOps.mkdirp(c, remoteDir);
            uploadFile(t, c, local, SftpOps.join(remoteDir, local.getFileName().toString()), r);
        }
    }

    private void uploadDir(Task t, ChannelSftp c, Path dir, String remotePath, ConflictResolver r) throws Exception {
        try (Stream<Path> st = Files.list(dir)) {
            for (Path child : st.sorted().toList()) {
                checkCancel(t);
                String rp = SftpOps.join(remotePath, child.getFileName().toString());
                if (Files.isDirectory(child)) {
                    SftpOps.mkdirp(c, rp);
                    uploadDir(t, c, child, rp, r);
                } else {
                    uploadFile(t, c, child, rp, r);
                }
            }
        }
    }

    private void uploadFile(Task t, ChannelSftp c, Path local, String target, ConflictResolver r) throws Exception {
        checkCancel(t);
        t.currentFile = SftpOps.nameOf(target);
        Conflict policy = Conflict.OVERWRITE;
        try {
            c.stat(target);
            if (r != null) policy = r.resolve(SftpOps.nameOf(target));
        } catch (SftpException notExists) {
            policy = Conflict.OVERWRITE;
        }
        if (policy == Conflict.SKIP) {
            t.transferred += Files.size(local);
            bump(t);
            return;
        }
        if (policy == Conflict.RENAME) target = renameRemote(c, target);
        String part = target + ".part";
        try (InputStream in = Files.newInputStream(local)) {
            c.put(in, part, monitor(t), ChannelSftp.OVERWRITE);
        } catch (Exception e) {
            try {
                c.rm(part);
            } catch (Exception ignored) {
            }
            throw e;
        }
        if (policy == Conflict.OVERWRITE) {
            try {
                c.rm(target);
            } catch (SftpException ignored) {
            }
        }
        c.rename(part, target);
    }

    // ---------- download ----------

    private void doDownload(Task t, String remote, Path localDir, ConflictResolver r) throws Exception {
        ChannelSftp c = t.sftp;
        SftpATTRS st = c.stat(remote);
        if (st.isDir() && !st.isLink()) {
            String base = SftpOps.join(localDir.toString(), SftpOps.nameOf(remote));
            t.totalBytes = remoteSize(c, remote);
            Files.createDirectories(Path.of(base));
            downloadDir(t, c, remote, Path.of(base), r);
        } else {
            t.totalBytes = st.getSize();
            downloadFile(t, c, remote, localDir, r);
        }
    }

    private void downloadDir(Task t, ChannelSftp c, String remotePath, Path localPath, ConflictResolver r) throws Exception {
        Files.createDirectories(localPath);
        for (SftpOps.Entry e : SftpOps.list(c, remotePath)) {
            checkCancel(t);
            String rp = SftpOps.join(remotePath, e.name());
            if (e.dir()) {
                downloadDir(t, c, rp, localPath.resolve(e.name()), r);
            } else {
                downloadFile(t, c, rp, localPath, r);
            }
        }
    }

    private void downloadFile(Task t, ChannelSftp c, String remote, Path localDir, ConflictResolver r) throws Exception {
        checkCancel(t);
        String name = SftpOps.nameOf(remote);
        t.currentFile = name;
        Path target = localDir.resolve(name);
        Conflict policy = Conflict.OVERWRITE;
        if (Files.exists(target) && r != null) policy = r.resolve(name);
        if (policy == Conflict.SKIP) {
            t.transferred += c.stat(remote).getSize();
            bump(t);
            return;
        }
        if (policy == Conflict.RENAME) {
            target = Path.of(freeName(target.toString(), p -> Files.exists(Path.of(p))));
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (OutputStream os = Files.newOutputStream(part)) {
            InputStream is = c.get(remote, monitor(t));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            is.close();
        } catch (Exception e) {
            try {
                Files.deleteIfExists(part);
            } catch (Exception ignored) {
            }
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------- helpers ----------

    private SftpProgressMonitor monitor(Task t) {
        return new SftpProgressMonitor() {
            long lastNs = System.nanoTime();
            long lastBytes;

            @Override
            public void init(int op, String src, String dest, long max) {
                if (max > 0) t.totalBytes = max;
            }

            @Override
            public boolean count(long soFar) {
                t.transferred = soFar;
                long now = System.nanoTime();
                if (now - lastNs > 500_000_000L) {
                    double bps = (soFar - lastBytes) / ((now - lastNs) / 1e9);
                    t.speedText = Fmt.speed(bps);
                    lastNs = now;
                    lastBytes = soFar;
                    bump(t);
                }
                return !t.cancel;
            }

            @Override
            public void end() {}
        };
    }

    private void bump(Task t) {
        long now = System.currentTimeMillis();
        if (now - t.lastNotify > 150) changed(t);
    }

    private void changed(Task t) {
        t.lastNotify = System.currentTimeMillis();
        notifyAllListeners();
    }

    private void notifyAllListeners() {
        for (Listener l : listeners) {
            try {
                l.transfersChanged();
            } catch (Exception ignored) {
            }
        }
    }

    private void checkCancel(Task t) {
        if (t.cancel) throw new CancelledException();
    }

    private static long sizeOfLocal(Path dir) {
        try (Stream<Path> st = Files.walk(dir)) {
            return st.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return -1;
        }
    }

    private static long remoteSize(ChannelSftp c, String remote) throws SftpException {
        SftpATTRS a = c.stat(remote);
        if (a.isDir() && !a.isLink()) {
            long sum = 0;
            for (SftpOps.Entry e : SftpOps.list(c, remote)) {
                if (e.dir()) sum += remoteSize(c, remote + "/" + e.name());
                else sum += e.size();
            }
            return sum;
        }
        return a.getSize();
    }

    private static String renameRemote(ChannelSftp c, String target) throws SftpException {
        return freeName(target, p -> {
            try {
                c.stat(p);
                return true;
            } catch (SftpException e) {
                return false;
            }
        });
    }

    /** 目标名被占用时生成 "name (1).ext" 形式的可用名。 */
    public static String freeName(String name, Predicate<String> exists) {
        if (!exists.test(name)) return name;
        String base;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        } else {
            base = name;
        }
        for (int i = 1; i < 10_000; i++) {
            String cand = base + " (" + i + ")" + ext;
            if (!exists.test(cand)) return cand;
        }
        return name + "-" + System.currentTimeMillis();
    }
}
