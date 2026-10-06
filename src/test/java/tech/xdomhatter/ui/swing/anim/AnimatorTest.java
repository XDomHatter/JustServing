package tech.xdomhatter.ui.swing.anim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimatorTest {
    private boolean savedEnabled;
    private float savedSpeed;
    private FakeClock clock;
    private Ticker ticker;
    private Animator anim;

    @BeforeEach
    void setup() {
        savedEnabled = Motion.enabled();
        savedSpeed = Motion.speed();
        Motion.setEnabled(true);
        Motion.setSpeed(1f);
        clock = new FakeClock();
        ticker = new Ticker(clock, () -> { }, () -> { });
        anim = new Animator(ticker);
    }

    @AfterEach
    void restore() {
        Motion.setEnabled(savedEnabled);
        Motion.setSpeed(savedSpeed);
    }

    @Test
    void progressRunsAndCompletes() {
        Recorder r = new Recorder();
        anim.animate(100, Easing.LINEAR, r::set, r::done);

        assertTrue(ticker.isRunning());
        clock.now += 50;
        ticker.tickNow();
        clock.now += 50;
        ticker.tickNow();

        assertTrue(r.values.size() >= 2, "应有多次回调: " + r.values);
        assertEquals(1f, r.values.get(r.values.size() - 1), 1e-6f, "末次进度应为 1");
        assertEquals(1, r.doneCount, "onDone 恰好一次");
        assertFalse(ticker.isRunning(), "完成后应停表");
        assertEquals(0, ticker.activeCount());

        float prev = -1;
        for (float v : r.values) {
            assertTrue(v >= prev - 1e-6f, "进度单调不减: " + prev + " → " + v);
            prev = v;
        }
    }

    @Test
    void easedProgressUsesEasing() {
        Recorder r = new Recorder();
        anim.animate(100, Easing.OUT_CUBIC, r::set, r::done);
        clock.now += 50;
        ticker.tickNow();
        // p = 0.5 → outCubic(0.5) = 0.875
        assertEquals(Easing.OUT_CUBIC.apply(0.5f), r.values.get(r.values.size() - 1), 1e-4f);
    }

    /** 复现调用方的续接模式：每次触发以当前显示值为 from，同 key 取消旧动画。 */
    @Test
    void sameKeySupersedesWithoutJumpBack() {
        float[] shown = {0f};
        List<Float> seq = new ArrayList<>();
        boolean[] aDone = {false};

        // 动画 A：0 → 100，时长 100ms
        float fromA = shown[0];
        anim.animate("k", 100, Easing.LINEAR, p -> {
            shown[0] = fromA + (100f - fromA) * p;
            seq.add(shown[0]);
        }, () -> aDone[0] = true);

        clock.now += 50;
        ticker.tickNow();
        assertEquals(50f, shown[0], 1e-4f, "中点插值");

        // 动画 B：同 key，目标 60，起点取当前显示值 50（时长 50ms，两次 tick 内完成）
        float fromB = shown[0];
        anim.animate("k", 50, Easing.LINEAR, p -> {
            shown[0] = fromB + (60f - fromB) * p;
            seq.add(shown[0]);
        }, null);

        clock.now += 30;
        ticker.tickNow();
        clock.now += 70;
        ticker.tickNow();

        assertFalse(aDone[0], "被取代的动画不应触发 onDone");
        assertEquals(60f, shown[0], 1e-4f, "最终到达新目标");
        assertEquals(3, seq.size(), "A 1 次 + B 2 次，A 取消后不再回调");
        float prev = -1;
        for (float v : seq) {
            assertTrue(v >= prev - 1e-4f, "显示值不得跳回: " + prev + " → " + v);
            prev = v;
        }
    }

    @Test
    void handleCancelStopsCallbacks() {
        Recorder r = new Recorder();
        Animator.AnimHandle h = anim.animate(100, Easing.LINEAR, r::set, r::done);
        clock.now += 20;
        ticker.tickNow();
        h.cancel();
        clock.now += 500;
        ticker.tickNow();
        assertEquals(1, r.values.size(), "cancel 后不再回调");
        assertEquals(0, r.doneCount, "cancel 不触发 onDone");
        assertFalse(ticker.isRunning());
    }

    @Test
    void cancelByKeyRemovesOnlyThatAnimation() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        anim.animate("k1", 50, Easing.LINEAR, a::set, a::done);
        anim.animate("k2", 50, Easing.LINEAR, b::set, b::done);
        anim.cancel("k1");
        assertEquals(1, ticker.activeCount());
        clock.now += 1000;   // dt 被钳制到 64ms，仍足以完成 50ms 的动画
        ticker.tickNow();
        assertEquals(0, a.doneCount);
        assertEquals(1, b.doneCount);
    }

    @Test
    void instantWhenMotionDisabled() {
        Motion.setEnabled(false);
        Recorder r = new Recorder();
        Animator.AnimHandle h = anim.animate(5000, Easing.LINEAR, r::set, r::done);
        assertEquals(List.of(1f), r.values, "减少动效：瞬时完成");
        assertEquals(1, r.doneCount);
        assertFalse(ticker.isRunning());
        h.cancel();   // no-op
        assertEquals(1, r.values.size());
    }

    @Test
    void instantWhenDurationZero() {
        Recorder r = new Recorder();
        anim.animate(0, Easing.LINEAR, r::set, r::done);
        assertEquals(List.of(1f), r.values);
        assertEquals(1, r.doneCount);
        assertFalse(ticker.isRunning());
    }

    @Test
    void runningAnimationFinishesWhenMotionDisabledMidway() {
        Recorder r = new Recorder();
        anim.animate(1000, Easing.LINEAR, r::set, r::done);
        clock.now += 100;
        ticker.tickNow();
        assertTrue(ticker.isRunning());
        Motion.setEnabled(false);
        ticker.tickNow();
        assertEquals(1f, r.values.get(r.values.size() - 1), 1e-6f, "运行中关闭应立即收尾");
        assertEquals(1, r.doneCount);
        assertFalse(ticker.isRunning());
    }
}
