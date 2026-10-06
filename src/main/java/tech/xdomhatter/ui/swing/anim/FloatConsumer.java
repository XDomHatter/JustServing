package tech.xdomhatter.ui.swing.anim;

/** float 版本的一元消费者（JDK 的 java.util.function 只提供 int/long/double 特化）。 */
@FunctionalInterface
public interface FloatConsumer {
    void accept(float value);
}
