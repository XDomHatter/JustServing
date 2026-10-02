package tech.xdomhatter.core.remote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 一次远程命令执行的句柄：状态、退出码与环形输出缓冲（新旧端都可轮询读取）。 */
public class ExecHandle {

    public enum State { RUNNING, DONE, FAILED, TIMEOUT, CANCELLED }

    public static final int MAX_LINES = 5000;

    private static final AtomicLong SEQ = new AtomicLong();

    public final long id = SEQ.incrementAndGet();
    public final String profileId;
    public final String profileName;
    public final String command;
    public final boolean nativeSsh;
    public final long startedAt = System.currentTimeMillis();

    public volatile State state = State.RUNNING;
    public volatile int exitCode = -1;
    public volatile String error = "";
    public volatile long finishedAt;
    public volatile boolean cancelRequested;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private volatile boolean truncated;
    private volatile Runnable killer;

    ExecHandle(String profileId, String profileName, String command, boolean nativeSsh) {
        this.profileId = profileId;
        this.profileName = profileName;
        this.command = command;
        this.nativeSsh = nativeSsh;
    }

    /** 由 CommandService 注入的终止手段（断开 channel / 杀进程）。 */
    void attach(Runnable killer) {
        this.killer = killer;
    }

    void append(String line, boolean stderr) {
        synchronized (lines) {
            if (lines.size() >= MAX_LINES) {
                lines.removeFirst();
                truncated = true;
            }
            lines.addLast(stderr ? "[err] " + line : line);
        }
    }

    void finish(State state, int exitCode, String error) {
        this.state = state;
        this.exitCode = exitCode;
        if (error != null) this.error = error;
        finishedAt = System.currentTimeMillis();
    }

    public void cancel() {
        if (isDone()) return;
        cancelRequested = true;
        Runnable k = killer;
        if (k != null) k.run();
    }

    public boolean isDone() {
        return state != State.RUNNING;
    }

    public boolean success() {
        return state == State.DONE && exitCode == 0;
    }

    /** 输出快照（最多 MAX_LINES 行）。 */
    public List<String> snapshotLines() {
        synchronized (lines) {
            List<String> out = new ArrayList<>(lines.size() + 1);
            if (truncated) out.add("…（输出过长，仅保留最近 " + MAX_LINES + " 行）");
            out.addAll(lines);
            return out;
        }
    }

    public long durationMs() {
        return (isDone() ? finishedAt : System.currentTimeMillis()) - startedAt;
    }

    public String stateText() {
        return switch (state) {
            case RUNNING -> "运行中";
            case DONE -> "完成";
            case FAILED -> "失败";
            case TIMEOUT -> "超时";
            case CANCELLED -> "已取消";
        };
    }

    public String summary() {
        String head = command.length() > 60 ? command.substring(0, 60) + "…" : command;
        return "#" + id + " " + profileName + "  " + stateText()
                + (isDone() ? "  " + durationMs() / 1000 + "s" : "") + "  " + head.replace("\n", " ");
    }
}
