package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.CommandTask;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.remote.ExecHandle;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** 远程命令：保存的任务可反复执行；上方也支持临时输入命令做单次执行。输出区显示最近一次执行。 */
public class CommandPanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));

    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"任务", "命令", "目标", "通道", "超时"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = Ui.table(model);
    private List<CommandTask> view = new ArrayList<>();

    private final JTextField adhocField = new JTextField();
    private final JComboBox<String> adhocTransport = new JComboBox<>(new String[]{"JSch", "原生 ssh"});
    private final JComboBox<ExecHandle> historyBox = new JComboBox<>();
    private final JTextArea output = new JTextArea();
    private final JLabel outState = Ui.hint("");

    private ExecHandle current;
    /** 输出轮询：执行中或内容有变化时刷新文本区。 */
    private final Timer poller = new Timer(250, e -> refreshOutput());
    private int lastLineCount = -1;
    private ExecHandle lastRendered;

    public CommandPanel(SwingApp app) {
        this.app = app;
        build();
        poller.start();
    }

    public JPanel panel() {
        return panel;
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // ---- 工具栏：任务管理 + 单次执行 ----
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton add = Ui.button("新建任务", "plus");
        add.addActionListener(e -> edit(null));
        JButton editB = Ui.button("编辑", "edit");
        editB.addActionListener(e -> {
            CommandTask t = selected();
            if (t != null) edit(t);
        });
        JButton del = Ui.button("删除", "trash");
        del.addActionListener(e -> {
            CommandTask t = selected();
            if (t == null) return;
            app.ctx().config.get().commandTasks.removeIf(x -> x == t);
            app.ctx().config.save();
            refresh();
        });
        JButton run = Ui.primary("运行", "zap");
        run.addActionListener(e -> runSelected());
        btns.add(add);
        btns.add(editB);
        btns.add(del);
        btns.add(run);

        adhocField.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT,
                "单次执行：输入命令后回车，如 systemctl status nginx");
        adhocField.addActionListener(e -> runAdhoc());
        adhocTransport.setToolTipText("执行通道");
        JButton adhocRun = Ui.button("执行", "zap");
        adhocRun.addActionListener(e -> runAdhoc());
        JPanel adhoc = new JPanel(new BorderLayout(6, 0));
        adhoc.setOpaque(false);
        adhoc.add(new JLabel("单次执行:"), BorderLayout.WEST);
        adhoc.add(adhocField, BorderLayout.CENTER);
        JPanel adhocRight = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        adhocRight.setOpaque(false);
        adhocRight.add(adhocTransport);
        adhocRight.add(adhocRun);
        adhoc.add(adhocRight, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout(8, 4));
        north.setOpaque(false);
        north.add(btns, BorderLayout.WEST);
        north.add(adhoc, BorderLayout.CENTER);

        // ---- 任务表 ----
        Ui.width(table, 0, 160);
        Ui.width(table, 1, 420);
        Ui.width(table, 2, 120);
        Ui.width(table, 3, 80);
        Ui.width(table, 4, 60);
        Ui.mono(table, 1);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) runSelected();
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(null);

        // ---- 输出区 ----
        output.setEditable(false);
        output.setFont(Ui.consoleFont());
        output.setBackground(Ui.c("EditorPane.background", new Color(0x1B1C1E)));
        output.setForeground(Ui.c("EditorPane.foreground", new Color(0xD0D0D0)));
        historyBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foc) {
                Component c = super.getListCellRendererComponent(list, value, index, sel, foc);
                if (value instanceof ExecHandle h) setText(h.summary());
                return c;
            }
        });
        historyBox.setPreferredSize(new Dimension(420, historyBox.getPreferredSize().height));
        historyBox.addActionListener(e -> {
            Object v = historyBox.getSelectedItem();
            if (v instanceof ExecHandle h) show(h);
        });
        JButton stop = Ui.danger("停止", "x");
        stop.addActionListener(e -> {
            if (current != null && !current.isDone()) {
                current.cancel();
                app.status("已请求取消 #" + current.id);
            }
        });
        JPanel outHead = new JPanel(new BorderLayout(6, 0));
        outHead.setOpaque(false);
        JPanel outHeadLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        outHeadLeft.setOpaque(false);
        outHeadLeft.add(new JLabel("输出:"));
        outHeadLeft.add(historyBox);
        outHeadLeft.add(outState);
        outHead.add(outHeadLeft, BorderLayout.WEST);
        outHead.add(stop, BorderLayout.EAST);
        JScrollPane outScroll = new JScrollPane(output);
        outScroll.setBorder(null);
        JPanel outPanel = new JPanel(new BorderLayout(4, 4));
        outPanel.setOpaque(false);
        outPanel.add(outHead, BorderLayout.NORTH);
        outPanel.add(outScroll, BorderLayout.CENTER);
        outPanel.setPreferredSize(new Dimension(0, 260));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, outPanel);
        split.setResizeWeight(0.45);
        split.setBorder(null);

        JLabel hint = Ui.hint(" 说明：任务可绑定到某台服务器或设为全局；双击任务行运行。原生 ssh 通道调用系统 ssh 客户端（凭 ssh-agent/默认密钥/~/.ssh/config 认证，不支持密码）。");
        panel.add(north, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
    }

    private CommandTask selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= view.size()) return null;
        return view.get(table.convertRowIndexToModel(row));
    }

    private SshProfile target() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            JOptionPane.showMessageDialog(app.frame(), "请先在左侧选择服务器", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return p;
    }

    private void runSelected() {
        CommandTask t = selected();
        if (t == null) return;
        // 任务绑定了服务器就执行在绑定目标上；全局任务执行在左侧选中的服务器上
        SshProfile p = t.profileId != null && !t.profileId.isEmpty()
                ? app.profileById(t.profileId)
                : target();
        if (p == null) {
            if (t.profileId != null && !t.profileId.isEmpty()) {
                JOptionPane.showMessageDialog(app.frame(), "任务绑定的服务器不存在，请先在左侧选择服务器", "提示",
                        JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }
        execute(p, t.command, t.transport, t.timeoutSec);
    }

    private void runAdhoc() {
        String cmd = adhocField.getText().strip();
        if (cmd.isEmpty()) return;
        SshProfile p = target();
        if (p == null) return;
        int timeout = Math.max(1, app.ctx().config.get().settings.commandTimeoutSec);
        execute(p, cmd, adhocTransport.getSelectedIndex() == 1 ? CommandTask.Transport.NATIVE : CommandTask.Transport.JSCH, timeout);
    }

    private void execute(SshProfile p, String command, CommandTask.Transport transport, int timeoutSec) {
        app.status("执行中: " + command);
        ExecHandle h = app.ctx().commands.execute(p, command, transport, timeoutSec);
        show(h);
        refreshHistory();
    }

    private void show(ExecHandle h) {
        current = h;
        lastLineCount = -1;
        refreshOutput();
    }

    private void refreshHistory() {
        ExecHandle sel = current;
        historyBox.removeAllItems();
        List<ExecHandle> hs = app.ctx().commands.history();
        if (hs.isEmpty()) return;
        for (ExecHandle h : hs) historyBox.addItem(h);
        if (sel != null) historyBox.setSelectedItem(sel);
    }

    private void refreshOutput() {
        ExecHandle h = current;
        if (h == null) {
            outState.setText("");
            return;
        }
        java.util.List<String> lines = h.snapshotLines();
        if (h != lastRendered || lines.size() != lastLineCount) {
            output.setText(String.join("\n", lines));
            lastLineCount = lines.size();
            lastRendered = h;
            output.setCaretPosition(output.getDocument().getLength());
        }
        outState.setText(h.profileName + " · " + h.stateText()
                + (h.isDone() ? " · 退出码 " + h.exitCode + " · " + h.durationMs() + "ms" : " · " + h.durationMs() / 1000 + "s")
                + (h.error.isEmpty() ? "" : " · " + h.error));
        if (h.isDone() && h != historyBox.getSelectedItem()) {
            historyBox.setSelectedItem(h);
        }
    }

    private void refresh() {
        view = app.ctx().config.get().commandTasks;
        model.setRowCount(0);
        for (CommandTask t : view) {
            String targetName = t.profileId == null || t.profileId.isEmpty()
                    ? "全局"
                    : app.ctx().config.profile(t.profileId).map(p -> p.name).orElse("?");
            model.addRow(new Object[]{t.name, t.command, targetName, t.transportText(), t.timeoutSec + "s"});
        }
    }

    /** 由 SwingApp 在服务器/配置变化时调用。 */
    public void refreshAll() {
        refresh();
        refreshHistory();
    }

    private void edit(CommandTask existing) {
        JTextField name = new JTextField();
        JTextField command = new JTextField();
        JComboBox<String> target = new JComboBox<>();
        target.addItem("全局（所有服务器）");
        for (SshProfile p : app.ctx().config.get().profiles) target.addItem(p.name);
        JComboBox<Integer> timeout = new JComboBox<>();
        for (int s : CommandTask.TIMEOUT_CHOICES) timeout.addItem(s);
        timeout.setEditable(true);
        JCheckBox nativeSsh = new JCheckBox("使用原生 ssh 客户端", false);
        if (existing != null) {
            name.setText(existing.name);
            command.setText(existing.command);
            if (existing.profileId == null || existing.profileId.isEmpty()) target.setSelectedIndex(0);
            else {
                for (int i = 0; i < app.ctx().config.get().profiles.size(); i++) {
                    if (app.ctx().config.get().profiles.get(i).id.equals(existing.profileId)) {
                        target.setSelectedIndex(i + 1);
                        break;
                    }
                }
            }
            timeout.setSelectedItem(existing.timeoutSec);
            nativeSsh.setSelected(existing.transport == CommandTask.Transport.NATIVE);
        }
        while (true) {
            JPanel p = SwingUtil.form(new String[]{"任务名:", "命令:", "目标:", "超时(秒):", ""},
                    new JComponent[]{name, command, target, timeout, nativeSsh});
            int r = JOptionPane.showConfirmDialog(app.frame(), p,
                    existing == null ? "新建命令任务" : "编辑命令任务",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            if (name.getText().isBlank() || command.getText().isBlank()) {
                JOptionPane.showMessageDialog(app.frame(), "任务名和命令不能为空");
                continue;
            }
            int sec;
            try {
                sec = Integer.parseInt(String.valueOf(timeout.getSelectedItem()).strip());
                if (sec <= 0) throw new NumberFormatException();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(app.frame(), "超时必须是正整数（秒）");
                continue;
            }
            CommandTask t = existing == null ? new CommandTask() : existing;
            t.name = name.getText().strip();
            t.command = command.getText().strip();
            t.profileId = target.getSelectedIndex() <= 0 ? "" : app.ctx().config.get().profiles.get(target.getSelectedIndex() - 1).id;
            t.timeoutSec = sec;
            t.transport = nativeSsh.isSelected() ? CommandTask.Transport.NATIVE : CommandTask.Transport.JSCH;
            if (existing == null) {
                t.id = tech.xdomhatter.core.store.ConfigStore.newId();
                app.ctx().config.get().commandTasks.add(t);
            }
            app.ctx().config.save();
            refresh();
            return;
        }
    }
}
