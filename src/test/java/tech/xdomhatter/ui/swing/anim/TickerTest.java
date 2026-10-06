package tech.xdomhatter.ui.swing.anim;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickerTest {
    private boolean savedEnabled;
    private float savedSpeed;

    @BeforeEach
    void setup() {
        savedEnabled = Motion.enabled();
        savedSpeed = Motion.speed();
        Motion.setEnabled(true);
        Motion.setSpeed(1f);
    }

    @AfterEach
    void restore() {
        Motion.setEnabled(savedEnabled);
        Motion.setSpeed(savedSpeed);
    }

    @Test
    void stopsWhenIdleAndRestartsOnNewAnimation() {
        FakeClock clock = new FakeClock();
        int[] starts = {0};
        int[] stops = {0};
        Ticker ticker = new Ticker(clock, () -> starts[0]++, () -> stops[0]++);
        Animator anim = new Animator(ticker);
        Recorder r = new Recorder();

        anim.animate(50, Easing.LINEAR, r::set, r::done);
        assertTrue(ticker.isRunning());
        assertEquals(1, starts[0], "添加动画应启动时钟");

        clock.now += 50;
        ticker.tickNow();
        assertFalse(ticker.isRunning(), "集合清空后应停表");
        assertEquals(1, stops[0]);
        assertEquals(1, r.doneCount);
        assertEquals(0, ticker.activeCount());

        // 再次添加 → 重新启动
        anim.animate(50, Easing.LINEAR, r::set, r::done);
        assertTrue(ticker.isRunning());
        assertEquals(2, starts[0]);
        clock.now += 50;
        ticker.tickNow();
        assertEquals(2, stops[0]);
        assertEquals(2, r.doneCount);
    }

    @Test
    void dtClampedTo64ms() {
        FakeClock clock = new FakeClock();
        Ticker ticker = new Ticker(clock, () -> { }, () -> { });
        Animator anim = new Animator(ticker);
        Recorder r = new Recorder();

        anim.animate(1000, Easing.LINEAR, r::set, r::done);
        clock.now += 10_000;   // 模拟长时间停顿（如窗口挂起）
        ticker.tickNow();
        // dt 被钳制到 64ms：进度 = 64/1000，而非直接完成
        assertEquals(64f / 1000f, r.values.get(r.values.size() - 1), 1e-6f);
        assertEquals(0, r.doneCount);
        assertTrue(ticker.isRunning());
    }

    @Test
    void speedMultiplierAppliesPerFrame() {
        FakeClock clock = new FakeClock();
        Ticker ticker = new Ticker(clock, () -> { }, () -> { });
        Animator anim = new Animator(ticker);
        Recorder r = new Recorder();
        Motion.setSpeed(2f);

        anim.animate(100, Easing.LINEAR, r::set, r::done);
        clock.now += 30;   // dt = 30 * 2 = 60
        ticker.tickNow();
        assertEquals(0.6f, r.values.get(r.values.size() - 1), 1e-4f);
    }

    @Test
    void onDoneCanStartNewAnimation() {
        FakeClock clock = new FakeClock();
        Ticker ticker = new Ticker(clock, () -> { }, () -> { });
        Animator anim = new Animator(ticker);
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        Recorder doneOfFirst = new Recorder();

        anim.animate(40, Easing.LINEAR, first::set,
                () -> {
                    doneOfFirst.done();
                    anim.animate(40, Easing.LINEAR, second::set, second::done);
                });
        clock.now += 40;
        ticker.tickNow();

        assertEquals(1, doneOfFirst.doneCount, "onDone 应被触发一次");
        assertTrue(ticker.isRunning(), "onDone 里启动的新动画应让时钟继续运行");
        clock.now += 40;
        ticker.tickNow();
        assertFalse(ticker.isRunning());
        assertEquals(1, second.doneCount);
    }
}
