package tech.xdomhatter.ui.swing.anim;

import java.util.prefs.Preferences;

/**
 * 全局动效配置：减少动效开关 + 全局速度倍率（0.5×–2×），用 Preferences 持久化。
 * 「减少动效」开启（enabled=false）时：新动画瞬时完成，运行中的动画在下一帧立即收尾。
 */
public final class Motion {
    private static final Preferences PREFS = Preferences.userNodeForPackage(Motion.class);
    private static final String KEY_ENABLED = "motion.enabled";
    private static final String KEY_SPEED = "motion.speed";

    private static volatile boolean enabled = PREFS.getBoolean(KEY_ENABLED, true);
    private static volatile float speed = clamp(PREFS.getFloat(KEY_SPEED, 1.0f));

    private Motion() { }

    public static boolean enabled() { return enabled; }

    public static void setEnabled(boolean v) {
        enabled = v;
        PREFS.putBoolean(KEY_ENABLED, v);
    }

    /** 全局速度倍率，0.5×–2×，每帧实时生效。 */
    public static float speed() { return speed; }

    public static void setSpeed(float v) {
        speed = clamp(v);
        PREFS.putFloat(KEY_SPEED, speed);
    }

    private static float clamp(float v) {
        return Math.max(0.5f, Math.min(2f, v));
    }
}
