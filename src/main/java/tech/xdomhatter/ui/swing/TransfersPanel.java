package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.sftp.TransferService;
import tech.xdomhatter.ui.swing.anim.Animator;
import tech.xdomhatter.ui.swing.anim.Easing;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TransfersPanel {
    private static final int COL_PROGRESS = 2;
    private static final int COL_CHECK = 6;
    private static final String[] COLUMNS = {"方向", "任务", "进度", "速度", "状态", "错误", ""};

    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = Ui.table(model);
    private final JScrollPane scroll = new JScrollPane(table);
    private final Animator animator = new Animator();
    /** 行主键（Task 实例，identity equals），与模型行一一对应。 */
    private final List<Object> keys = new ArrayList<>();
    /** Task → 动画显示状态（渲染器共享实例，动画值一律从模型侧读取）。 */
    private final Map<TransferService.Task, TaskView> states = new HashMap<>();
    private List<TransferService.Task> view = new ArrayList<>();

    public TransfersPanel(SwingApp app) {
        this.app = app;
        app.ctx().transfers.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        build();
    }

    public JPanel panel() {
        return panel;
    }

    /** 单个任务的动画显示状态。 */
    private static final class TaskView {
        float dispPct;          // 显示用进度（向真实进度缓动逼近）
        float check;            // 完成勾选缩放（OVERSHOOT 0→1，可短暂超过 1）
        TransferService.State lastState;
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.getColumnModel().getColumn(COL_PROGRESS).setCellRenderer(new BarRenderer());
        table.getColumnModel().getColumn(COL_CHECK).setCellRenderer(new CheckRenderer());
        Ui.width(table, 0, 60);
        Ui.width(table, 1, 320);
        Ui.width(table, 2, 180);
        Ui.width(table, 3, 110);
        Ui.width(table, 4, 160);
        Ui.width(table, 5, 120);
        Ui.width(table, COL_CHECK, 36);
        Ui.mono(table, 1);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton cancel = Ui.button("取消选中任务", "x");
        cancel.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0 || row >= view.size()) return;
            app.ctx().transfers.cancel(view.get(table.convertRowIndexToModel(row)));
        });
        JButton clear = Ui.button("清除已完成", "trash");
        clear.addActionListener(e -> app.ctx().transfers.clearFinished());
        btns.add(cancel);
        btns.add(clear);
        btns.add(Ui.hint("提示：上传/下载任务在“文件”页发起；目录会递归传输"));
        panel.add(btns, BorderLayout.NORTH);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
    }

    private void refresh() {
        List<TransferService.Task> tasks = app.ctx().transfers.tasks();
        Point viewPos = TableSync.viewPosition(table);
        var sel = TableSync.selectedKeys(table, keys);

        TableSync.syncRows(model, keys, tasks,
                t -> {
                    var task = (TransferService.Task) t;
                    return new Object[]{dirText(task), task.source, Math.round(dispPct(task)),
                            task.speedText, stateText(task), task.error, ""};
                },
                (row, k) -> {
                    var t = (TransferService.Task) k;
                    TableSync.setCell(model, row, 0, dirText(t));
                    TableSync.setCell(model, row, 1, t.source);
                    TableSync.setCell(model, row, COL_PROGRESS, Math.round(dispPct(t)));
                    TableSync.setCell(model, row, 3, t.speedText);
                    TableSync.setCell(model, row, 4, stateText(t));
                    TableSync.setCell(model, row, 5, t.error);
                });
        view = tasks;
        TableSync.restore(table, keys, sel, viewPos);

        for (TransferService.Task t : tasks) {
            TaskView tv = states.get(t);
            if (tv == null) {
                tv = new TaskView();
                tv.dispPct = t.percent();
                tv.lastState = t.state;
                if (t.state == TransferService.State.DONE) tv.check = 1f;  // 历史任务不弹跳
                states.put(t, tv);
            }
            animateProgress(t, tv);
            if (tv.lastState != TransferService.State.DONE && t.state == TransferService.State.DONE) {
                animateCheck(tv);
            }
            tv.lastState = t.state;
        }
        // 清理已消失任务的状态与动画
        states.keySet().removeIf(t -> {
            if (tasks.contains(t)) return false;
            animator.cancel(t);        // 进度动画
            animator.cancel(states.get(t));  // 勾选动画
            return true;
        });
    }

    private float dispPct(TransferService.Task t) {
        TaskView tv = states.get(t);
        return tv != null ? tv.dispPct : t.percent();
    }

    private void animateProgress(TransferService.Task t, TaskView tv) {
        int real = t.percent();
        int shown = Math.round(tv.dispPct);
        if (shown == real) return;
        if (!panel.isShowing()) {
            tv.dispPct = real;
            updateProgressCell(t, real);
            return;
        }
        int from = shown;
        int to = real;
        animator.animate(t, 400, Easing.OUT_CUBIC, p -> {
            int v = Math.round(from + (to - from) * p);
            tv.dispPct = v;
            updateProgressCell(t, v);
        }, null);
    }

    /** 动画帧：写模型进度列触发该单元格重绘，渲染器从 states 读取显示值。 */
    private void updateProgressCell(TransferService.Task t, int v) {
        int row = keys.indexOf(t);
        TableSync.setCell(model, row, COL_PROGRESS, v);
    }

    private void animateCheck(TaskView tv) {
        if (!panel.isShowing()) {
            tv.check = 1f;
            table.repaint();
            return;
        }
        animator.animate(tv, 460, Easing.OVERSHOOT, p -> {
            tv.check = p;
            table.repaint();   // 动画帧内只 repaint
        }, null);
    }

    private static String dirText(TransferService.Task t) {
        return t.direction == TransferService.Direction.UPLOAD ? "上传" : "下载";
    }

    private static String stateText(TransferService.Task t) {
        return switch (t.state) {
            case QUEUED -> "排队中";
            case RUNNING -> t.currentFile.isEmpty() ? "传输中" : "传输中: " + t.currentFile;
            case DONE -> "完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    /** 进度条渲染器：共享实例，自身不保存任何动画状态，显示值来自 panel 侧 states。 */
    private class BarRenderer extends JProgressBar implements TableCellRenderer {
        BarRenderer() {
            setStringPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            int m = t.convertRowIndexToModel(r);
            float pct = m >= 0 && m < view.size() ? dispPct(view.get(m)) : 0;
            setValue(Math.round(pct));
            return this;
        }
    }

    /**
     * 行尾完成勾选：绿色圆底 + 白色对勾，OVERSHOOT 从 0 缩放到 1。
     * 渲染器共享实例：paintTask 仅在 get→paint 之间传递行上下文（每次渲染前都会重设），
     * 动画值实时读 states，不含动画状态。
     */
    private class CheckRenderer extends DefaultTableCellRenderer {
        private TransferService.Task paintTask;

        CheckRenderer() {
            setHorizontalAlignment(CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, "", sel, foc, r, c);
            int m = t.convertRowIndexToModel(r);
            paintTask = m >= 0 && m < view.size() ? view.get(m) : null;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);   // 选中背景
            if (paintTask == null) return;
            TaskView tv = states.get(paintTask);
            if (tv == null || tv.check <= 0f) return;
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                float alpha = Math.min(1f, tv.check * 2f);
                g.setComposite(AlphaComposite.SrcOver.derive(alpha));
                g.translate(getWidth() / 2.0, getHeight() / 2.0);
                g.scale(tv.check, tv.check);
                double d = 16;
                g.setColor(Ui.OK);
                g.fill(new Ellipse2D.Double(-d / 2, -d / 2, d, d));
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(-4, 0, -1, 3));
                g.draw(new Line2D.Double(-1, 3, 4.5, -4));
            } finally {
                g.dispose();
            }
        }
    }
}
