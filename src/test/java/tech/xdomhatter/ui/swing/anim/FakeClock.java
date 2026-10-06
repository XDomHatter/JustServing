package tech.xdomhatter.ui.swing.anim;

import java.util.function.LongSupplier;

/** 可编程假时钟，用于手动驱动 Ticker 帧而不依赖真实时间。 */
final class FakeClock implements LongSupplier {
    long now = 1_000;

    @Override
    public long getAsLong() {
        return now;
    }
}
