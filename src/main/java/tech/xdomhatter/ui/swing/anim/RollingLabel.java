package tech.xdomhatter.ui.swing.anim;

import tech.xdomhatter.ui.swing.Ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 数值滚动标签：新值到来时从当前显示值平滑滚动过去，保留 1 位小数；
 * 数值跨越 60 / 85 阈值时颜色即时切换为 {@link Ui#OK} / {@link Ui#WARN} / {@link Ui#DANGER}。
 *
 * <p>自绘实现而非继承 JLabel：JLabel.setText 在动画帧内会触发 revalidate，
 * 这里动画帧内只 {@code repaint()}。preferredSize 按最宽文本固定，避免布局抖动。</p>
 */
public class RollingLabel extends JComponent {
    private final String format;
    private final Animator animator = new Animator();
    private double shown = Double.NaN;   // NaN 表示无值

    public RollingLabel() { this("%.1f%%"); }

    public RollingLabel(String format) {
        this.format = format;
        setOpaque(false);
        Font base = getFont() != null ? getFont() : Ui.monoFont();
        setFont(base.deriveFont(Font.BOLD, 17f));
        FontMetrics fm = getFontMetrics(getFont());
        setPreferredSize(new Dimension(fm.stringWidth(String.format(format, 100.0)) + 6,
                fm.getHeight() + 2));
    }

    /** 设定目标值；组件未显示（如标签页未激活）时直接跳到目标值。 */
    public void setValue(double v) {
        if (Double.isNaN(shown) || !isShowing() || Math.abs(v - shown) < 0.05) {
            shown = v;
            repaint();
            return;
        }
        double from = shown;
        double to = v;
        animator.animate(this, 700, Easing.OUT_CUBIC, p -> {
            shown = from + (to - from) * p;
            repaint();
        }, null);
    }

    /** 清空数值显示。 */
    public void clear() {
        shown = Double.NaN;
        animator.cancel(this);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (Double.isNaN(shown)) return;
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setFont(getFont());
        g2.setColor(thresholdColor(shown));
        String text = String.format(format, shown);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, getWidth() - fm.stringWidth(text),
                (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
    }

    /** 数值阈值配色：&lt;60 绿 / &lt;85 橙 / 其余红。 */
    public static Color thresholdColor(double v) {
        return v < 60 ? Ui.OK : v < 85 ? Ui.WARN : Ui.DANGER;
    }
}
