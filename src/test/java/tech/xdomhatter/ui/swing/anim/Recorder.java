package tech.xdomhatter.ui.swing.anim;

import java.util.ArrayList;
import java.util.List;

/** 记录 setter / onDone 回调的测试桩。 */
final class Recorder {
    final List<Float> values = new ArrayList<>();
    int doneCount;

    void set(float p) {
        values.add(p);
    }

    void done() {
        doneCount++;
    }
}
