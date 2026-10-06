package tech.xdomhatter.ui.swing.anim;

/**
 * 动画门面。setter 收到的是缓动后的 0→1 进度。
 *
 * <p><b>可中断续接</b>：以同一 key 再次 {@link #animate} 会取消旧动画（不触发其
 * onDone）。调用方应把「当前显示值」作为新动画的起点（from）——例如
 * {@code float from = 当前显示值; animate(key, ms, easing, p -> 显示 = from + (目标 - from) * p, null)}
 * ——即可平滑续接，不会跳回起点。</p>
 *
 * <p>仅在 EDT 上调用。</p>
 */
public final class Animator {
    private final Ticker ticker;

    public Animator() { this(Ticker.get()); }

    Animator(Ticker ticker) { this.ticker = ticker; }

    /** 无键动画：各次调用相互独立。 */
    public AnimHandle animate(int durationMs, Easing easing, FloatConsumer setter, Runnable onDone) {
        return animate(null, durationMs, easing, setter, onDone);
    }

    /** 键控动画：同 key 的活动动画会被新动画取代（取消，不触发其 onDone）。 */
    public AnimHandle animate(Object key, int durationMs, Easing easing,
                              FloatConsumer setter, Runnable onDone) {
        if (!Motion.enabled() || durationMs <= 0) {
            setter.accept(1f);
            if (onDone != null) onDone.run();
            return () -> { };
        }
        if (key != null) ticker.cancel(key);
        Ticker.Entry e = new Ticker.Entry(key, durationMs, easing, setter, onDone);
        ticker.add(e);
        return () -> ticker.remove(e);
    }

    /** 取消该 key 的活动动画（若有），不触发 onDone。 */
    public void cancel(Object key) {
        ticker.cancel(key);
    }

    /** 动画句柄；cancel 后动画停止且不触发 onDone，可重复调用。 */
    public interface AnimHandle {
        void cancel();
    }
}
