package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.OpenBinding;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class OpenWithPanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"模式", "命令（%f 为文件占位符）", "启用"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = Ui.table(model);
    private List<OpenBinding> view = new ArrayList<>();

    public OpenWithPanel(SwingApp app) {
        this.app = app;
        build();
        refresh();
    }

    public JPanel panel() {
        return panel;
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        Ui.width(table, 0, 200);
        Ui.width(table, 1, 420);
        Ui.mono(table, 1);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton add = Ui.button("新建", "plus");
        add.addActionListener(e -> edit(null));
        JButton editB = Ui.button("编辑", "edit");
        editB.addActionListener(e -> {
            OpenBinding b = selected();
            if (b != null) edit(b);
        });
        JButton del = Ui.button("删除", "trash");
        del.addActionListener(e -> {
            OpenBinding b = selected();
            if (b == null) return;
            app.ctx().config.get().bindings.removeIf(x -> x == b);
            app.ctx().config.save();
            refresh();
        });
        JButton up = Ui.button("上移", "up");
        up.addActionListener(e -> move(-1));
        JButton down = Ui.button("下移", "down");
        down.addActionListener(e -> move(1));
        btns.add(add);
        btns.add(editB);
        btns.add(del);
        btns.add(up);
        btns.add(down);
        JLabel hint = Ui.hint(" 说明：模式支持 *.log / .txt / 精确文件名 / * 兜底；命令如 code %f、notepad %f；按顺序匹配，无命中时用系统默认方式打开");
        panel.add(btns, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
    }

    private OpenBinding selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= view.size()) return null;
        return view.get(table.convertRowIndexToModel(row));
    }

    private void move(int delta) {
        OpenBinding b = selected();
        if (b == null) return;
        List<OpenBinding> list = app.ctx().config.get().bindings;
        int i = list.indexOf(b);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        list.remove(i);
        list.add(j, b);
        app.ctx().config.save();
        refresh();
    }

    private void edit(OpenBinding existing) {
        JTextField pattern = new JTextField();
        JTextField command = new JTextField();
        JCheckBox enabled = new JCheckBox("启用", true);
        if (existing != null) {
            pattern.setText(existing.pattern);
            command.setText(existing.command);
            enabled.setSelected(existing.enabled);
        }
        while (true) {
            JPanel p = SwingUtil.form(new String[]{"文件模式:", "命令模板:", ""},
                    new JComponent[]{pattern, command, enabled});
            int r = JOptionPane.showConfirmDialog(app.frame(), p,
                    existing == null ? "新建打开方式" : "编辑打开方式", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            if (pattern.getText().isBlank() || command.getText().isBlank()) {
                JOptionPane.showMessageDialog(app.frame(), "模式和命令不能为空");
                continue;
            }
            OpenBinding b = existing == null ? new OpenBinding() : existing;
            b.pattern = pattern.getText().strip();
            b.command = command.getText().strip();
            b.enabled = enabled.isSelected();
            if (existing == null) {
                app.ctx().config.get().bindings.add(b);
            }
            app.ctx().config.save();
            refresh();
            return;
        }
    }

    private void refresh() {
        view = app.ctx().config.get().bindings;
        model.setRowCount(0);
        for (OpenBinding b : view) {
            model.addRow(new Object[]{b.pattern, b.command, b.enabled ? "是" : "否"});
        }
    }
}
