package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.apps.AppManager;
import tech.xdomhatter.core.model.ManagedApp;
import tech.xdomhatter.core.model.SshProfile;

import javax.swing.*;
import java.awt.*;

/** 应用日志查看：stdout/stderr 最近 N 行，手动或定时刷新。 */
public class AppLogDialog {
    private AppLogDialog() {}

    public static void show(SwingApp app, SshProfile p, ManagedApp a) {
        JDialog dlg = new JDialog(app.frame(), a.name + " — 日志", false);
        dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JTextArea area = new JTextArea(28, 100);
        area.setEditable(false);
        area.setFont(Ui.monoFont());
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(null);

        JComboBox<String> lines = new JComboBox<>(new String[]{"200 行", "500 行", "2000 行"});
        lines.setSelectedIndex(1);
        JCheckBox auto = new JCheckBox("自动刷新(2s)");
        JButton refresh = Ui.button("刷新", "refresh");
        JButton close = Ui.button("关闭", "x");
        close.addActionListener(e -> dlg.dispose());
        refresh.addActionListener(e -> load(app, p, a, lines, area));

        Timer timer = new Timer(2000, e -> {
            if (auto.isSelected()) load(app, p, a, lines, area);
        });
        timer.start();
        auto.addActionListener(e -> {
            if (auto.isSelected()) load(app, p, a, lines, area);
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        top.add(refresh);
        top.add(lines);
        top.add(auto);
        top.add(close);

        dlg.setLayout(new BorderLayout(8, 8));
        dlg.add(top, BorderLayout.NORTH);
        dlg.add(scroll, BorderLayout.CENTER);
        dlg.getRootPane().setDefaultButton(refresh);
        dlg.pack();
        dlg.setLocationRelativeTo(app.frame());
        dlg.setVisible(true);
        load(app, p, a, lines, area);
    }

    private static void load(SwingApp app, SshProfile p, ManagedApp a, JComboBox<String> lines, JTextArea area) {
        int n = switch (lines.getSelectedIndex()) {
            case 0 -> 200;
            case 2 -> 2000;
            default -> 500;
        };
        new SwingWorker<AppManager.LogTail, Void>() {
            @Override
            protected AppManager.LogTail doInBackground() throws Exception {
                if (!app.ctx().ssh.isConnected(p.id)) app.ctx().ssh.connect(p);
                return app.ctx().apps.tail(p.id, a, n);
            }

            @Override
            protected void done() {
                try {
                    AppManager.LogTail t = get();
                    Point viewPos = area.getParent() instanceof JViewport vp ? vp.getViewPosition() : null;
                    area.setText("──── 标准输出 ────\n" + String.join("\n", t.out())
                            + "\n\n──── 标准错误 ────\n" + String.join("\n", t.err()));
                    if (viewPos != null && area.getParent() instanceof JViewport vp) {
                        vp.setViewPosition(viewPos);
                    } else {
                        area.setCaretPosition(0);
                    }
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    area.setText("读取日志失败: " + t.getMessage());
                }
            }
        }.execute();
    }
}
