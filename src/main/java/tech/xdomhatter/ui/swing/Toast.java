package tech.xdomhatter.ui.swing;

import tech.xdomhatter.ui.swing.anim.Animator;
import tech.xdomhatter.ui.swing.anim.Easing;

import javax.swing.*;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/**
 * 主窗口内的消息气泡：底部居中淡入，停留约 2 秒后淡出。
 * 重复 show 以最新文本重新计时，不叠加多个气泡。
 * 挂在主窗口 LayeredPane 的 POPUP_LAYER 上，仅在 EDT 上使用。
 */
final class Toast {
    private static final int HOLD_MS = 2000;
    private static final int IN_MS = 160;
    private static final int OUT_MS = 260;

    private final JLayeredPane layered;
    private final Bubble bubble = new Bubble();
    private final Animator animator = new Animator();
    private final Timer hideTimer = new Timer(HOLD_MS, e -> fadeOut());
    private boolean hiding;

    Toast(JFrame frame) {
        this.layered = frame.getLayeredPane();
        hideTimer.setRepeats(false);
        bubble.setVisible(false);
        layered.add(bubble, JLayeredPane.POPUP_LAYER);
    }

    void show(String text) {
        bubble.setText(text);
        Dimension s = bubble.sizeFor();
        bubble.setSize(s);
        int x = Math.max(8, (layered.getWidth() - s.width) / 2);
        int y = Math.max(8, layered.getHeight() - s.height - 56);   // 状态栏上方
        bubble.setLocation(x, y);
        hiding = false;
        hideTimer.stop();
        animator.cancel(bubble);   // 打断进行中的淡出，否则新气泡会被旧淡出的 onDone 隐藏
        bubble.setVisible(true);
        bubble.repaint();
        animator.animate(bubble, IN_MS, Easing.OUT_CUBIC, p -> {
            bubble.alpha = p;
            bubble.repaint();
        }, null);
        hideTimer.restart();
    }

    private void fadeOut() {
        if (hiding || !bubble.isVisible()) return;
        hiding = true;
        animator.animate(bubble, OUT_MS, Easing.OUT_CUBIC, p -> {
            bubble.alpha = 1f - p;
            bubble.repaint();
        }, () -> {
            bubble.setVisible(false);
            hiding = false;
        });
    }

    /** 圆角气泡：左侧绿色对勾圆点 + 文本；alpha 由动画驱动。 */
    private static final class Bubble extends JPanel {
        float alpha;

        Bubble() {
            setOpaque(false);
            setFocusable(false);
            setFont(UIManager.getFont("Label.font"));
        }

        void setText(String text) {
            putClientProperty("toast.text", text);
        }

        private String text() {
            Object t = getClientProperty("toast.text");
            return t == null ? "" : t.toString();
        }

        Dimension sizeFor() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = 14 + 22 + fm.stringWidth(text()) + 16;
            int h = 10 + fm.getHeight() + 10;
            return new Dimension(w, h);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setComposite(java.awt.AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
                boolean dark = Ui.isDark();
                g.setColor(dark ? new Color(0x2A2A2E) : Color.WHITE);
                g.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 14, 14));
                g.setColor(dark ? new Color(0x3C3C41) : new Color(0xD9D9DE));
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 14, 14));

                int cy = getHeight() / 2;
                int cx = 14 + 8;
                double d = 16;
                g.setColor(Ui.OK);
                g.fill(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(cx - 4, cy, cx - 1, cy + 3));
                g.draw(new Line2D.Double(cx - 1, cy + 3, cx + 4.5, cy - 4));

                FontMetrics fm = g.getFontMetrics();
                g.setColor(dark ? new Color(0xEAEAEA) : new Color(0x222222));
                g.drawString(text(), 14 + 22, cy + (fm.getAscent() - fm.getDescent()) / 2);
            } finally {
                g.dispose();
            }
        }
    }
}
