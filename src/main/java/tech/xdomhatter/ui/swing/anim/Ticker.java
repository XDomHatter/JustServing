package tech.xdomhatter.ui.swing.anim;

import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 全局唯一动画时钟（单例）。内部用一个约 16ms 的 {@link Timer}（合并模式）驱动；
 * 无活动动画时自动停表，不常驻唤醒 EDT。
 *
 * <p>仅应在 EDT 上调用。测试可用包私有构造注入时钟与 start/stop 钩子，
 * 手动调 {@link #tickNow()} 驱动帧，不触碰真实 Swing Timer。</p>
 */
public final class Ticker {
    private static Ticker instance;

    /** 单个动画在时钟里的调度数据。 */
    static final class Entry {
        final Object key;      // 可为 null（无键动画）
        final int durationMs;
        final Easing easing;
        final FloatConsumer setter;
        final Runnable onDone;
        float elapsed;

        Entry(Object key, int durationMs, Easing easing,
              FloatConsumer setter, Runnable onDone) {
            this.key = key;
            this.durationMs = Math.max(1, durationMs);
            this.easing = easing;
            this.setter = setter;
            this.onDone = onDone;
        }
    }

    private final LongSupplier clock;
    private final Runnable onStart;   // 可为 null（生产实现负责启动 Swing Timer）
    private final Runnable onStop;
    private Timer timer;              // 惰性创建，测试路径永远不会触达
    private long last;
    private boolean running;
    private final List<Entry> entries = new ArrayList<>();

    /** 生产单例。 */
    public static synchronized Ticker get() {
        if (instance == null) {
            instance = new Ticker(System::currentTimeMillis,
                    () -> instance.ensureTimer().start(),
                    () -> { if (instance.timer != null) instance.timer.stop(); });
        }
        return instance;
    }

    Ticker(LongSupplier clock, Runnable onStart, Runnable onStop) {
        this.clock = clock;
        this.onStart = onStart;
        this.onStop = onStop;
    }

    void add(Entry e) {
        entries.add(e);
        ensureStarted();
    }

    void remove(Entry e) {
        entries.remove(e);
        if (entries.isEmpty()) stop();
    }

    void cancel(Object key) {
        boolean removed = entries.removeIf(en -> en.key != null && en.key.equals(key));
        if (removed && entries.isEmpty()) stop();
    }

    /** 推进一帧。由 Swing Timer（或测试）调用。 */
    void tickNow() {
        if (!running || entries.isEmpty()) return;
        long now = clock.getAsLong();
        float dt = Math.min(64f, Math.max(0f, now - last)) * Motion.speed();
        last = now;
        for (Entry e : new ArrayList<>(entries)) {
            if (!entries.contains(e)) continue;   // 回调里已被取消/取代
            if (!Motion.enabled()) {              // 运行中关闭减少动效：立即收尾
                e.setter.accept(1f);
                finish(e);
                continue;
            }
            e.elapsed += dt;
            float p = Math.min(1f, e.elapsed / e.durationMs);
            e.setter.accept(e.easing.apply(p));
            if (p >= 1f) finish(e);
        }
        if (entries.isEmpty()) stop();
    }

    private void finish(Entry e) {
        entries.remove(e);
        if (e.onDone != null) e.onDone.run();
    }

    private void ensureStarted() {
        if (running) return;
        running = true;
        last = clock.getAsLong();
        if (onStart != null) onStart.run();
    }

    private void stop() {
        if (!running) return;
        running = false;
        if (onStop != null) onStop.run();
    }

    private Timer ensureTimer() {
        if (timer == null) {
            timer = new Timer(16, e -> tickNow());
            timer.setCoalesce(true);
        }
        return timer;
    }

    boolean isRunning() { return running; }

    int activeCount() { return entries.size(); }
}
