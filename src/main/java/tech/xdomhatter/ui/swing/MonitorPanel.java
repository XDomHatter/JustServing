package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.MonitorSnapshot;
import tech.xdomhatter.core.monitor.MonitorService;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.util.Fmt;
import tech.xdomhatter.ui.swing.anim.Animator;
import tech.xdomhatter.ui.swing.anim.Easing;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final Animator animator = new Animator();

    /** 表格行主键，与模型行一一对应，用于增量更新。 */
    private final List<Object> portKeys = new ArrayList<>();
    private final List<Object> procKeys = new ArrayList<>();
    /** 挂载点 → 磁盘行组件缓存，避免每 3 秒全量重建。 */
    private final Map<String, DiskRow> diskRows = new LinkedHashMap<>();

    private JTable portTable;
    private JTable procTable;

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

        portTable = Ui.table(portModel);
        procTable = Ui.table(procModel);
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
        procScroll.setBorder(BorderFactory.createTitledBorder("进程占用"));
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
            diskRows.values().forEach(animator::cancel);
            diskRows.clear();
            diskPanel.removeAll();
            portKeys.clear();
            procKeys.clear();
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

        updateDisks(s.disks);
        updatePorts(s.ports);
        updateProcs(s.procs);
    }

    // ---------- 磁盘区：按挂载点缓存行组件，仅数值变化时更新 ----------

    private static final class DiskRow {
        final JPanel panel = new JPanel(new BorderLayout(4, 1));
        final JLabel lbl = new JLabel();
        final JProgressBar bar = new JProgressBar(0, 100);

        DiskRow() {
            bar.setStringPainted(true);
            panel.add(lbl, BorderLayout.NORTH);
            panel.add(bar, BorderLayout.CENTER);
        }
    }

    private void updateDisks(List<MonitorSnapshot.Disk> disks) {
        boolean rowsChanged = false;
        for (MonitorSnapshot.Disk d : disks) {
            DiskRow row = diskRows.get(d.mount);
            if (row == null) {
                row = new DiskRow();
                diskRows.put(d.mount, row);
                diskPanel.add(row.panel);
                rowsChanged = true;
            }
            String text = d.mount + "  (" + d.fs + ")  " + Fmt.bytes(d.used) + " / " + Fmt.bytes(d.total);
            if (!text.equals(row.lbl.getText())) row.lbl.setText(text);
            setBarValue(row, d.percent());
        }
        // 移除已消失的挂载点
        var mounts = new java.util.HashSet<String>();
        for (MonitorSnapshot.Disk d : disks) mounts.add(d.mount);
        for (Iterator<Map.Entry<String, DiskRow>> it = diskRows.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, DiskRow> e = it.next();
            if (!mounts.contains(e.getKey())) {
                animator.cancel(e.getValue());
                diskPanel.remove(e.getValue().panel);
                it.remove();
                rowsChanged = true;
            }
        }
        // 快照行序变化时按快照顺序重排（df 输出顺序稳定，极少触发）
        Component[] comps = diskPanel.getComponents();
        boolean orderOk = comps.length == disks.size();
        for (int i = 0; orderOk && i < disks.size(); i++) {
            orderOk = comps[i] == diskRows.get(disks.get(i).mount).panel;
        }
        if (!orderOk) {
            diskPanel.removeAll();
            for (MonitorSnapshot.Disk d : disks) diskPanel.add(diskRows.get(d.mount).panel);
            rowsChanged = true;
        }
        if (rowsChanged) {
            diskPanel.revalidate();   // 仅行增删时调用
            diskPanel.repaint();
        }
    }

    private void setBarValue(DiskRow row, int pct) {
        row.bar.setForeground(pct < 60 ? Ui.OK : pct < 85 ? Ui.WARN : Ui.DANGER);
        int from = row.bar.getValue();
        if (from == pct || !panel.isShowing()) {
            row.bar.setValue(pct);
            row.bar.setString(pct + "%");
            return;
        }
        int to = pct;
        animator.animate(row, 750, Easing.OUT_CUBIC, p -> {
            int v = Math.round(from + (to - from) * p);
            row.bar.setValue(v);
            row.bar.setString(v + "%");
        }, null);
    }

    // ---------- 端口 / 进程表：按主键差异更新，保留选中与滚动 ----------

    private static String portKey(MonitorSnapshot.PortListen p) {
        return p.proto + "|" + p.addr + "|" + p.port;
    }

    private void updatePorts(List<MonitorSnapshot.PortListen> ports) {
        Point viewPos = TableSync.viewPosition(portTable);
        var sel = TableSync.selectedKeys(portTable, portKeys);
        Map<String, MonitorSnapshot.PortListen> byKey = new HashMap<>();
        var newKeys = new ArrayList<Object>(ports.size());
        for (MonitorSnapshot.PortListen p : ports) {
            String k = portKey(p);
            byKey.put(k, p);
            newKeys.add(k);
        }
        TableSync.syncRows(portModel, portKeys, newKeys,
                k -> {
                    var p = byKey.get(k);
                    return new Object[]{p.proto, p.addr, p.port,
                            p.process == null ? "-" : p.process, p.pid < 0 ? "-" : p.pid};
                },
                (row, k) -> {
                    var p = byKey.get(k);
                    TableSync.setCell(portModel, row, 0, p.proto);
                    TableSync.setCell(portModel, row, 1, p.addr);
                    TableSync.setCell(portModel, row, 2, p.port);
                    TableSync.setCell(portModel, row, 3, p.process == null ? "-" : p.process);
                    TableSync.setCell(portModel, row, 4, p.pid < 0 ? "-" : p.pid);
                });
        TableSync.restore(portTable, portKeys, sel, viewPos);
    }

    private void updateProcs(List<MonitorSnapshot.Proc> procs) {
        Point viewPos = TableSync.viewPosition(procTable);
        var sel = TableSync.selectedKeys(procTable, procKeys);
        Map<Long, MonitorSnapshot.Proc> byPid = new HashMap<>();
        var newKeys = new ArrayList<Object>(procs.size());
        for (MonitorSnapshot.Proc p : procs) {
            byPid.put(p.pid, p);
            newKeys.add(p.pid);
        }
        TableSync.syncRows(procModel, procKeys, newKeys,
                k -> {
                    var p = byPid.get(k);
                    return new Object[]{p.pid, p.user, p.cpu, p.mem,
                            Fmt.bytes(p.rssKb * 1024), p.elapsed, p.command};
                },
                (row, k) -> {
                    var p = byPid.get(k);
                    TableSync.setCell(procModel, row, 0, p.pid);
                    TableSync.setCell(procModel, row, 1, p.user);
                    TableSync.setCell(procModel, row, 2, p.cpu);
                    TableSync.setCell(procModel, row, 3, p.mem);
                    TableSync.setCell(procModel, row, 4, Fmt.bytes(p.rssKb * 1024));
                    TableSync.setCell(procModel, row, 5, p.elapsed);
                    TableSync.setCell(procModel, row, 6, p.command);
                });
        TableSync.restore(procTable, procKeys, sel, viewPos);
    }
}
