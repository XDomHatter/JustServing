package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.sftp.TransferService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

public class TransfersPanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"方向", "任务", "进度", "速度", "状态", "错误"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = Ui.table(model);
    private List<TransferService.Task> view = new ArrayList<>();

    public TransfersPanel(SwingApp app) {
        this.app = app;
        app.ctx().transfers.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        build();
    }

    public JPanel panel() {
        return panel;
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        table.getColumnModel().getColumn(2).setCellRenderer(new BarRenderer());
        Ui.width(table, 0, 60);
        Ui.width(table, 1, 320);
        Ui.width(table, 2, 180);
        Ui.width(table, 3, 110);
        Ui.width(table, 4, 160);
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
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
    }

    private void refresh() {
        view = app.ctx().transfers.tasks();
        model.setRowCount(0);
        for (TransferService.Task t : view) {
            model.addRow(new Object[]{
                    t.direction == TransferService.Direction.UPLOAD ? "上传" : "下载",
                    t.source,
                    t.percent(),
                    t.speedText,
                    stateText(t),
                    t.error});
        }
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

    private static class BarRenderer extends JProgressBar implements TableCellRenderer {
        BarRenderer() {
            setStringPainted(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            setValue(v instanceof Integer i ? i : 0);
            return this;
        }
    }
}
