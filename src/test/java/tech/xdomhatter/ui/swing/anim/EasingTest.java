package tech.xdomhatter.ui.swing.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EasingTest {

    @Test
    void endpoints() {
        for (Easing e : Easing.values()) {
            assertEquals(0f, e.apply(0f), 1e-6f, e.name() + " 起点");
            assertEquals(1f, e.apply(1f), 1e-6f, e.name() + " 终点");
        }
    }

    @Test
    void linearIdentity() {
        for (int i = 0; i <= 10; i++) {
            float t = i / 10f;
            assertEquals(t, Easing.LINEAR.apply(t), 1e-6f);
        }
    }

    @Test
    void outCubicFasterThanLinear() {
        assertTrue(Easing.OUT_CUBIC.apply(0.5f) > 0.5f);
        assertTrue(Easing.OUT_QUINT.apply(0.5f) > Easing.OUT_CUBIC.apply(0.5f));
        assertTrue(Easing.IN_CUBIC.apply(0.5f) < 0.5f);
        assertEquals(0.5f, Easing.IN_OUT_CUBIC.apply(0.5f), 1e-6f);
    }

    @Test
    void nonOvershootMonotonic() {
        for (Easing e : Easing.values()) {
            if (e == Easing.OVERSHOOT) continue;   // easeOutBack 允许中段超过 1 后回落
            float prev = -1;
            for (int i = 0; i <= 20; i++) {
                float v = e.apply(i / 20f);
                assertTrue(v >= prev - 1e-6f, e.name() + " 在 t=" + i + " 处回退");
                prev = v;
            }
        }
    }

    @Test
    void overshootExceedsOneMidway() {
        assertTrue(Easing.OVERSHOOT.apply(0.7f) > 1f, "中段应超过 1");
        assertTrue(Easing.OVERSHOOT.apply(0.9f) > 1f);
    }

    @Test
    void clampsOutOfRangeInput() {
        for (Easing e : Easing.values()) {
            assertEquals(0f, e.apply(-0.5f), 1e-6f, e.name());
            assertEquals(1f, e.apply(1.5f), 1e-6f, e.name());
        }
    }
}
