package tech.xdomhatter.ui.swing;

import tech.xdomhatter.ui.swing.anim.Animator;
import tech.xdomhatter.ui.swing.anim.Easing;
import tech.xdomhatter.ui.swing.anim.RollingLabel;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;

/**
 * 简易历史曲线图：圆角卡片、平滑曲线、渐变填充，颜色随浅/深主题自动适配。
 */
class LineChart extends JComponent {
    private static final int MAX_POINTS = 120;
    private static final int TWEEN_MS = 700;

    private final String title;
    private final double max;
    private final ArrayDeque<Double> vals = new ArrayDeque<>();
    private final Animator animator = new Animator();
    private final RollingLabel valueLabel = new RollingLabel();
    /**
     * 显示序列：由补间动画向目标序列 vals 平滑逼近；绘制使用它而非 vals。
     */
    private double[] display = new double[0];

    LineChart(String title, double max) {
        this.title = title;
        this.max = max;
        setOpaque(false);
        add(valueLabel);
    }

    private static double[] toArray(ArrayDeque<Double> vals) {
        double[] a = new double[vals.size()];
        int i = 0;
        for (double v : vals) a[i++] = v;
        return a;
    }

    void add(double v) {
        vals.addLast(v);
        while (vals.size() > MAX_POINTS) vals.removeFirst();
        valueLabel.setValue(v);

        double[] to = toArray(vals);
        // 起点为当前显示状态；新出现的点从上一个显示值处"萌芽"，不跳变
        double[] from = new double[to.length];
        for (int i = 0; i < to.length; i++) {
            from[i] = i < display.length ? display[i] : (i > 0 ? from[i - 1] : v);
        }
        if (!isShowing()) {
            display = to;
            repaint();
            return;
        }
        display = from;
        // 键控动画：采样间隔内未完成的补间会被下一次 add 取消，从当前显示值续接
        animator.animate(this, TWEEN_MS, Easing.OUT_CUBIC, p -> {
            for (int i = 0; i < to.length; i++) {
                display[i] = from[i] + (to[i] - from[i]) * p;
            }
            repaint();
        }, null);
    }

    void clear() {
        vals.clear();
        display = new double[0];
        animator.cancel(this);
        valueLabel.clear();
        repaint();
    }

    @Override
    public void doLayout() {
        // 无布局管理器，右上角数值标签手工定位
        Dimension pref = valueLabel.getPreferredSize();
        valueLabel.setBounds(getWidth() - 12 - pref.width, 8, pref.width, pref.height);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        int w = getWidth();
        int h = getHeight();
        if (w < 40 || h < 40) return;

        Color panelBg = Ui.c("Panel.background", Color.WHITE);
        Color border = Ui.c("Component.borderColor", Color.GRAY);
        Color text = Ui.c("Label.foreground", Color.DARK_GRAY);
        Color dim = Ui.dim();
        Color accent = Ui.ACCENT;

        int arc = 14;
        g.setColor(panelBg);
        g.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, arc, arc));
        g.setColor(border);
        g.draw(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, arc, arc));

        int pad = 12;
        int top = 34;
        int bottom = h - 16;

        g.setColor(new Color(border.getRed(), border.getGreen(), border.getBlue(), 70));
        for (int i = 0; i <= 4; i++) {
            int y = top + (int) (i * (bottom - top) / 4.0);
            g.drawLine(pad, y, w - pad, y);
        }

        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.setColor(dim);
        g.drawString(title, pad, 22);

        double[] arr = display;
        if (arr.length == 0) {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 13f));
            g.setColor(dim);
            String empty = "暂无数据";
            int ew = g.getFontMetrics().stringWidth(empty);
            g.drawString(empty, (w - ew) / 2.0f, (top + bottom) / 2.0f);
            return;
        }

        double span = Math.max(1, arr.length - 1);
        double step = (w - 2.0 * pad) / span;
        double[] xs = new double[arr.length];
        double[] ys = new double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            xs[i] = pad + i * step;
            ys[i] = bottom - Math.min(1, Math.max(0, arr[i]) / max) * (bottom - top);
        }

        Path2D line = new Path2D.Double();
        if (arr.length == 1) {
            g.setColor(accent);
            g.fillOval((int) xs[0] - 3, (int) ys[0] - 3, 6, 6);
            return;
        }
        line.moveTo(xs[0], ys[0]);
        for (int i = 1; i < arr.length; i++) {
            double mx = (xs[i - 1] + xs[i]) / 2;
            double my = (ys[i - 1] + ys[i]) / 2;
            line.quadTo(xs[i - 1], ys[i - 1], mx, my);
        }
        line.lineTo(xs[arr.length - 1], ys[arr.length - 1]);

        Path2D fill = (Path2D) line.clone();
        fill.lineTo(xs[arr.length - 1], bottom);
        fill.lineTo(xs[0], bottom);
        fill.closePath();
        g.setPaint(new GradientPaint(0, top, new Color(accent.getRGB() & 0xFFFFFF | 0x50000000, true),
            0, bottom, new Color(accent.getRGB() & 0xFFFFFF, true)));
        g.fill(fill);

        g.setColor(accent);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
    }
}
