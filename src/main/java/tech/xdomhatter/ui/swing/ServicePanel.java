package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.ServiceInfo;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.remote.ServiceManager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** systemd 服务管理：列出单元、启停/重启/自启，查看状态与日志。 */
public class ServicePanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));

    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"服务单元", "状态", "自启", "描述"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = Ui.table(model);
    private List<ServiceInfo> view = new ArrayList<>();
    private final JTextField filter = new JTextField();
    private final JLabel count = Ui.hint("");

    public ServicePanel(SwingApp app) {
        this.app = app;
        build();
    }

    public JPanel panel() {
        return panel;
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton refresh = Ui.button("刷新", "refresh");
        refresh.addActionListener(e -> refresh());

        filter.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "按名称/描述过滤");
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refill();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refill();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refill();
            }
        });
        JPanel filterBox = new JPanel(new BorderLayout(6, 0));
        filterBox.setOpaque(false);
        filterBox.add(new JLabel("过滤:"), BorderLayout.WEST);
        filterBox.add(filter, BorderLayout.CENTER);

        JButton start = Ui.button("启动", "play");
        start.addActionListener(e -> act("start"));
        JButton stop = Ui.button("停止", "stop");
        stop.addActionListener(e -> act("stop"));
        JButton restart = Ui.button("重启", "refresh");
        restart.addActionListener(e -> act("restart"));
        JButton enable = Ui.button("自启", "check");
        enable.addActionListener(e -> act("enable"));
        JButton disable = Ui.button("禁自启", "x");
        disable.addActionListener(e -> act("disable"));
        JButton status = Ui.button("状态/日志", "chart");
        status.addActionListener(e -> showDetail());

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btns.add(refresh);
        btns.add(filterBox);
        btns.add(start);
        btns.add(stop);
        btns.add(restart);
        btns.add(enable);
        btns.add(disable);
        btns.add(status);

        Ui.width(table, 0, 240);
        Ui.width(table, 1, 130);
        Ui.width(table, 2, 90);
        Ui.mono(table, 0);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) showDetail();
            }
        });

        JLabel hint = Ui.hint(" 说明：列出 systemd 服务单元（运行中的在前）；启停/重启需 root 或 sudo 免密；状态按钮显示 systemctl status 与最近日志。");
        JPanel north = new JPanel(new BorderLayout(8, 4));
        north.setOpaque(false);
        north.add(btns, BorderLayout.NORTH);
        north.add(count, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
    }

    private ServiceInfo selected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= view.size()) return null;
        return view.get(table.convertRowIndexToModel(row));
    }

    private SshProfile requireProfile() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            JOptionPane.showMessageDialog(app.frame(), "请先在左侧选择服务器", "提示", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return p;
    }

    public void refresh() {
        SshProfile p = requireProfile();
        if (p == null) return;
        app.status("正在获取服务列表: " + p.name + " ...");
        new SwingWorker<List<ServiceInfo>, Void>() {
            @Override
            protected List<ServiceInfo> doInBackground() throws Exception {
                if (!app.ctx().ssh.isConnected(p.id)) app.ctx().ssh.connect(p);
                return app.ctx().services.list(p.id);
            }

            @Override
            protected void done() {
                try {
                    view = get();
                    refill();
                    app.status("已加载 " + view.size() + " 个服务单元 " + p.name);
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    app.status("获取服务列表失败");
                    JOptionPane.showMessageDialog(app.frame(),
                            "获取服务列表失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void refill() {
        String f = filter.getText().strip().toLowerCase();
        model.setRowCount(0);
        int shown = 0;
        for (ServiceInfo s : view) {
            if (!f.isEmpty() && !(s.unit.toLowerCase().contains(f) || s.description.toLowerCase().contains(f))) continue;
            shown++;
            model.addRow(new Object[]{s.unit, s.stateText(), s.enabled, s.description});
        }
        count.setText(" 共 " + shown + " / " + view.size() + " 个服务单元");
    }

    private void act(String action) {
        ServiceInfo s = selected();
        if (s == null) {
            JOptionPane.showMessageDialog(app.frame(), "请先选择服务", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SshProfile p = requireProfile();
        if (p == null) return;
        app.status(action + " " + s.unit + " ...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return app.ctx().services.action(p.id, s.unit, action);
            }

            @Override
            protected void done() {
                try {
                    String out = get();
                    app.status(action + " " + s.unit + " 完成");
                    if (!out.isEmpty()) {
                        JOptionPane.showMessageDialog(app.frame(), s.unit + " → " + action + "\n" + out,
                                "结果", JOptionPane.PLAIN_MESSAGE);
                    }
                    refresh();
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    app.status(action + " " + s.unit + " 失败");
                    JOptionPane.showMessageDialog(app.frame(),
                            action + " 失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showDetail() {
        ServiceInfo s = selected();
        if (s == null) return;
        SshProfile p = requireProfile();
        if (p == null) return;
        app.status("获取 " + s.unit + " 状态 ...");
        new SwingWorker<ServiceManager.ServiceDetail, Void>() {
            @Override
            protected ServiceManager.ServiceDetail doInBackground() throws Exception {
                return app.ctx().services.detail(p.id, s.unit, 100);
            }

            @Override
            protected void done() {
                try {
                    ServiceManager.ServiceDetail d = get();
                    JTextArea area = new JTextArea(28, 88);
                    area.setEditable(false);
                    area.setFont(Ui.monoFont());
                    area.setText(String.join("\n", d.status()) + "\n\n──────── 最近日志 ────────\n" + String.join("\n", d.log()));
                    area.setCaretPosition(0);
                    JOptionPane.showMessageDialog(app.frame(), new JScrollPane(area),
                            s.unit + " 状态与日志", JOptionPane.PLAIN_MESSAGE);
                    app.status("就绪");
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(app.frame(),
                            "获取状态失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
