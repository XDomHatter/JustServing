package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.MonitorSnapshot;
import tech.xdomhatter.core.monitor.MonitorService;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.util.Fmt;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MonitorPanel {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final LineChart cpuChart = new LineChart("CPU 使用率", 100);
    private final LineChart ramChart = new LineChart("内存使用率", 100);
    private final JPanel diskPanel = new JPanel();
    private final DefaultTableModel portModel = model("协议", "监听地址", "端口", "进程", "PID");
    private final DefaultTableModel procModel = model("PID", "用户", "CPU%", "MEM%", "RSS", "运行时长", "命令");
    private final JLabel updateLabel = Ui.hint(" 等待数据...（连接服务器后自动开始采样）");
    private SshProfile current;

    private final MonitorService.Listener listener = new MonitorService.Listener() {
        @Override
        public void onSnapshot(String profileId, MonitorSnapshot s) {
            SwingUtilities.invokeLater(() -> {
                if (current != null && profileId.equals(current.id)) update(s);
            });
        }

        @Override
        public void onError(String profileId, String message) {
            SwingUtilities.invokeLater(() -> {
                if (current != null && profileId.equals(current.id)) {
                    updateLabel.setText(" " + message);
                }
            });
        }
    };

    public MonitorPanel(SwingApp app) {
        this.app = app;
        app.ctx().monitor.addListener(listener);
        build();
    }

    public JPanel panel() {
        return panel;
    }

    private static DefaultTableModel model(Object... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        JPanel charts = new JPanel(new GridLayout(1, 2, 10, 0));
        charts.setPreferredSize(new Dimension(0, 210));
        charts.add(cpuChart);
        charts.add(ramChart);

        JPanel diskHolder = new JPanel(new BorderLayout());
        diskPanel.setLayout(new GridLayout(0, 2, 14, 6));
        diskHolder.add(diskPanel, BorderLayout.NORTH);
        JScrollPane diskScroll = new JScrollPane(diskHolder);
        diskScroll.setBorder(BorderFactory.createTitledBorder("磁盘"));

        JTable portTable = Ui.table(portModel);
        JTable procTable = Ui.table(procModel);
        Ui.width(portTable, 0, 70);
        Ui.width(portTable, 1, 150);
        Ui.width(portTable, 2, 70);
        Ui.width(portTable, 3, 150);
        Ui.width(portTable, 4, 70);
        Ui.width(procTable, 0, 70);
        Ui.width(procTable, 1, 90);
        Ui.width(procTable, 2, 60);
        Ui.width(procTable, 3, 60);
        Ui.width(procTable, 4, 90);
        Ui.width(procTable, 5, 90);
        Ui.width(procTable, 6, 340);
        for (int c : new int[]{0, 1, 2, 3, 4}) Ui.mono(portTable, c);
        for (int c : new int[]{0, 2, 3, 4, 5, 6}) Ui.mono(procTable, c);
        JScrollPane portScroll = new JScrollPane(portTable);
        portScroll.setBorder(BorderFactory.createTitledBorder("端口占用"));
        JScrollPane procScroll = new JScrollPane(procTable);
        procScroll.setBorder(BorderFactory.createTitledBorder("进程占用（按 CPU 排序）"));
        JSplitPane tables = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, portScroll, procScroll);
        tables.setResizeWeight(0.45);
        tables.setBorder(null);
        JSplitPane middle = new JSplitPane(JSplitPane.VERTICAL_SPLIT, diskScroll, tables);
        middle.setResizeWeight(0.3);
        middle.setBorder(null);

        panel.add(charts, BorderLayout.NORTH);
        panel.add(middle, BorderLayout.CENTER);
        panel.add(updateLabel, BorderLayout.SOUTH);
    }

    public void onConnected(SshProfile p) {
        current = p;
        app.ctx().monitor.setInterval(app.ctx().config.get().settings.monitorIntervalMs);
        app.ctx().monitor.start(p.id);
    }

    public void onDisconnected(String profileId) {
        if (current != null && current.id.equals(profileId)) {
            current = null;
            cpuChart.clear();
            ramChart.clear();
            diskPanel.removeAll();
            portModel.setRowCount(0);
            procModel.setRowCount(0);
            updateLabel.setText(" 等待数据...（连接服务器后自动开始采样）");
            panel.revalidate();
            panel.repaint();
        }
    }

    private void update(MonitorSnapshot s) {
        if (s.cpuPercent >= 0) cpuChart.add(s.cpuPercent);
        if (s.memTotal > 0 && s.memAvailable >= 0) {
            ramChart.add((s.memTotal - s.memAvailable) * 100.0 / s.memTotal);
        }
        updateLabel.setText(" 更新于 " + TS.format(Instant.ofEpochMilli(s.timestamp).atZone(ZoneId.systemDefault()))
                + "    端口 " + s.ports.size() + " 项    进程 " + s.procs.size() + " 项（无 PID 的端口需要 root 权限查看）");

        diskPanel.removeAll();
        for (MonitorSnapshot.Disk d : s.disks) {
            JPanel row = new JPanel(new BorderLayout(4, 1));
            JLabel lbl = new JLabel(d.mount + "  (" + d.fs + ")  " + Fmt.bytes(d.used) + " / " + Fmt.bytes(d.total));
            JProgressBar bar = new JProgressBar(0, 100);
            int pct = d.percent();
            bar.setValue(pct);
            bar.setStringPainted(true);
            bar.setString(pct + "%");
            bar.setForeground(pct < 60 ? Ui.OK : pct < 85 ? Ui.WARN : Ui.DANGER);
            row.add(lbl, BorderLayout.NORTH);
            row.add(bar, BorderLayout.CENTER);
            diskPanel.add(row);
        }
        diskPanel.revalidate();
        diskPanel.repaint();

        portModel.setRowCount(0);
        for (MonitorSnapshot.PortListen p : s.ports) {
            portModel.addRow(new Object[]{p.proto, p.addr, p.port,
                    p.process == null ? "-" : p.process, p.pid < 0 ? "-" : p.pid});
        }
        procModel.setRowCount(0);
        for (MonitorSnapshot.Proc p : s.procs) {
            procModel.addRow(new Object[]{p.pid, p.user, p.cpu, p.mem,
                    Fmt.bytes(p.rssKb * 1024), p.elapsed, p.command});
        }
    }
}
