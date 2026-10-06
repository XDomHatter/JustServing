package tech.xdomhatter.ui.swing.anim;

/**
 * 缓动函数。入参 t 会被钳制到 [0,1]，端点精确返回 0/1；
 * 除 OVERSHOOT（中段允许超过 1）外，返回值始终在 [0,1] 内。
 */
public enum Easing {
    LINEAR {
        @Override float ease(float t) { return t; }
    },
    OUT_CUBIC {
        @Override float ease(float t) { float u = 1 - t; return 1 - u * u * u; }
    },
    IN_CUBIC {
        @Override float ease(float t) { return t * t * t; }
    },
    IN_OUT_CUBIC {
        @Override float ease(float t) {
            return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
        }
    },
    OUT_QUINT {
        @Override float ease(float t) { float u = 1 - t; return 1 - u * u * u * u * u; }
    },
    OVERSHOOT {
        @Override float ease(float t) {
            final float c1 = 1.70158f, c3 = c1 + 1;
            float u = t - 1;
            return 1 + c3 * u * u * u + c1 * u * u;
        }
    };

    /** 钳制 t 到 [0,1] 后套用曲线。 */
    public final float apply(float t) {
        if (t <= 0) return 0f;
        if (t >= 1) return 1f;
        return ease(t);
    }

    abstract float ease(float t);
}
