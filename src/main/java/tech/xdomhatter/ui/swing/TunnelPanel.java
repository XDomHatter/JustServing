package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.AppConfig;
import tech.xdomhatter.core.model.FrpProxy;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.model.TunnelSpec;
import tech.xdomhatter.core.tunnel.FrpManager;
import tech.xdomhatter.core.tunnel.SshTunnelManager;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class TunnelPanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(6, 6));
    private List<TunnelSpec> tunnelView = List.of();
    private List<FrpProxy> proxyView = List.of();

    private final DefaultTableModel tunnelModel = model("名称", "类型", "监听", "目标", "状态");
    private final DefaultTableModel proxyModel = model("名称", "类型", "本地", "远程端口", "启用");
    private final JTable tunnelTable = new JTable(tunnelModel);
    private final JTable proxyTable = new JTable(proxyModel);
    private final JLabel frpsLabel = Ui.hint(" frps 状态: 点击“刷新状态”");
    private final JButton startFrpcBtn = Ui.button("启动 frpc", "play");
    private final JButton stopFrpcBtn = Ui.button("停止 frpc", "stop");

    public TunnelPanel(SwingApp app) {
        this.app = app;
        app.ctx().tunnels.addListener(() -> SwingUtilities.invokeLater(this::refresh));
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
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JTabbedPane sub = new JTabbedPane();
        tunnelTable.setRowHeight(26);
        proxyTable.setRowHeight(26);
        Ui.mono(tunnelTable, 2);
        Ui.mono(tunnelTable, 3);
        Ui.mono(proxyTable, 2);

        // --- SSH 隧道 ---
        JPanel sshPanel = new JPanel(new BorderLayout(6, 6));
        JPanel sshBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton newT = Ui.button("新建隧道", "plus");
        newT.addActionListener(e -> {
            SshProfile p = app.selectedProfile();
            if (p == null) {
                info("请先在左侧选择服务器");
                return;
            }
            TunnelSpec t = TunnelDialog.show(app, null);
            if (t != null) {
                app.ctx().config.get().tunnels.add(t);
                app.ctx().config.save();
                refresh();
            }
        });
        JButton editT = Ui.button("编辑", "edit");
        editT.addActionListener(e -> editTunnel());
        JButton delT = Ui.button("删除", "trash");
        delT.addActionListener(e -> deleteTunnel());
        JButton startT = Ui.button("启动", "play");
        startT.addActionListener(e -> startTunnel());
        JButton stopT = Ui.button("停止", "stop");
        stopT.addActionListener(e -> stopTunnel());
        sshBtns.add(newT);
        sshBtns.add(editT);
        sshBtns.add(delT);
        sshBtns.add(startT);
        sshBtns.add(stopT);
        sshPanel.add(sshBtns, BorderLayout.NORTH);
        sshPanel.add(new JScrollPane(tunnelTable), BorderLayout.CENTER);
        sub.addTab("SSH 隧道 (本地/远程转发)", sshPanel);

        // --- frp ---
        JPanel frpPanel = new JPanel(new BorderLayout(6, 6));
        JPanel frpBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton newP = Ui.button("新建代理", "plus");
        newP.addActionListener(e -> addProxy());
        JButton delP = Ui.button("删除代理", "trash");
        delP.addActionListener(e -> deleteProxy());
        startFrpcBtn.setText("启动 frpc");
        startFrpcBtn.setIcon(Ui.icon("play"));
        stopFrpcBtn.setText("停止 frpc");
        stopFrpcBtn.setIcon(Ui.icon("stop"));
        startFrpcBtn.addActionListener(e -> startFrpc());
        stopFrpcBtn.addActionListener(e -> stopFrpc());
        JButton frpcLog = Ui.button("frpc 日志", "file");
        frpcLog.addActionListener(e -> showFrpcLog());
        JButton proxyStatus = Ui.button("代理状态", "chart");
        proxyStatus.addActionListener(e -> showProxyStatus());
        frpBtns.add(newP);
        frpBtns.add(delP);
        frpBtns.add(startFrpcBtn);
        frpBtns.add(stopFrpcBtn);
        frpBtns.add(frpcLog);
        frpBtns.add(proxyStatus);
        frpPanel.add(frpBtns, BorderLayout.NORTH);
        frpPanel.add(new JScrollPane(proxyTable), BorderLayout.CENTER);
        sub.addTab("frp 代理 (本机 frpc)", frpPanel);

        // --- frps ---
        JPanel frpsPanel = new JPanel(new BorderLayout(6, 6));
        JPanel frpsBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton frpsStatus = Ui.button("刷新状态", "refresh");
        frpsStatus.addActionListener(e -> refreshFrps());
        JButton frpsStart = Ui.button("启动", "play");
        frpsStart.addActionListener(e -> frpsAction("start"));
        JButton frpsStop = Ui.button("停止", "stop");
        frpsStop.addActionListener(e -> frpsAction("stop"));
        JButton frpsRestart = Ui.button("重启", "refresh");
        frpsRestart.addActionListener(e -> frpsAction("restart"));
        JButton frpsLog = Ui.button("日志", "file");
        frpsLog.addActionListener(e -> showFrpsLog());
        JButton frpsConf = Ui.button("配置文件…", "edit");
        frpsConf.addActionListener(e -> showFrpsConfig());
        JButton frpsDetect = Ui.button("自动检测 frps", "file");
        frpsDetect.addActionListener(e -> detectFrps());
        JButton frpsSetup = Ui.button("自动配置 systemd", "sliders");
        frpsSetup.addActionListener(e -> autoSetupSystemd());
        JButton frpsDeploy = Ui.button("一键部署 frps…", "upload");
        frpsDeploy.addActionListener(e -> deployFrps());
        JButton frpsToken = Ui.button("设置 frps token", "edit");
        frpsToken.addActionListener(e -> setFrpsToken());
        frpsBtns.add(frpsStatus);
        frpsBtns.add(frpsStart);
        frpsBtns.add(frpsStop);
        frpsBtns.add(frpsRestart);
        frpsBtns.add(frpsLog);
        frpsBtns.add(frpsConf);
        frpsBtns.add(frpsDetect);
        frpsBtns.add(frpsSetup);
        frpsBtns.add(frpsDeploy);
        frpsBtns.add(frpsToken);
        frpsPanel.add(frpsBtns, BorderLayout.NORTH);
        JPanel frpsInfo = new JPanel(new BorderLayout());
        frpsInfo.add(frpsLabel, BorderLayout.NORTH);
        frpsInfo.add(Ui.hint(" 配置文件：可视化编辑服务器 frps 配置（自动备份，可同步 token）；自动检测：探测 frps 路径/配置/单元并回填设置；"
                + "自动配置：单元缺失时生成 systemd unit 并 enable（frps 手动运行中则仅开机自启）。需 root 或 sudo 免密。"), BorderLayout.SOUTH);
        frpsPanel.add(frpsInfo, BorderLayout.CENTER);
        sub.addTab("frp 服务端 (frps)", frpsPanel);

        panel.add(sub, BorderLayout.CENTER);
    }

    // ---------- SSH 隧道 ----------

    private TunnelSpec selectedTunnel() {
        int row = tunnelTable.getSelectedRow();
        if (row < 0 || row >= tunnelView.size()) return null;
        return tunnelView.get(tunnelTable.convertRowIndexToModel(row));
    }

    public void refresh() {
        tunnelModel.setRowCount(0);
        SshProfile p = app.selectedProfile();
        if (p != null) {
            tunnelView = app.ctx().config.tunnelsOf(p.id);
            for (TunnelSpec t : tunnelView) {
                SshTunnelManager.Active a = app.ctx().tunnels.get(t.id);
                String status = a == null ? "未运行" : a.statusText();
                String detail = a != null && !a.detail.isEmpty() ? " (" + a.detail + ")" : "";
                tunnelModel.addRow(new Object[]{t.name, t.type == TunnelSpec.Type.REMOTE ? "远程 -R" : "本地 -L",
                        t.type == TunnelSpec.Type.REMOTE ? "服务器:" + t.listenPort : t.bindAddr + ":" + t.listenPort,
                        t.targetHost + ":" + t.targetPort, status + detail});
            }
        } else {
            tunnelView = List.of();
        }
        proxyModel.setRowCount(0);
        if (p != null) {
            proxyView = app.ctx().config.proxiesOf(p.id);
            for (FrpProxy px : proxyView) {
                proxyModel.addRow(new Object[]{px.name, px.type, px.localIp + ":" + px.localPort, px.remotePort,
                        px.enabled ? "是" : "否"});
            }
        } else {
            proxyView = List.of();
        }
        boolean frpcRunning = p != null && app.ctx().frp.frpcRunning(p.id);
        startFrpcBtn.setEnabled(p != null && !frpcRunning);
        stopFrpcBtn.setEnabled(frpcRunning);
    }

    private void editTunnel() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        TunnelSpec t = selectedTunnel();
        if (t == null) {
            info("请先选择隧道");
            return;
        }
        TunnelSpec edited = TunnelDialog.show(app, t);
        if (edited != null) {
            refresh();
        }
    }

    private void deleteTunnel() {
        TunnelSpec t = selectedTunnel();
        if (t == null) {
            info("请先选择隧道");
            return;
        }
        if (JOptionPane.showConfirmDialog(app.frame(), "删除隧道 “" + t.name + "”？", "确认",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        app.ctx().tunnels.stop(t.id);
        app.ctx().config.get().tunnels.removeIf(x -> x.id.equals(t.id));
        app.ctx().config.save();
        refresh();
    }

    private void startTunnel() {
        TunnelSpec t = selectedTunnel();
        if (t == null) {
            info("请先选择隧道");
            return;
        }
        String name = t.name;
        new Thread(() -> {
            try {
                app.ctx().tunnels.start(t);
                app.status("隧道已启动: " + name);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(app.frame(),
                        "启动失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        }, "tunnel-start").start();
    }

    private void stopTunnel() {
        TunnelSpec t = selectedTunnel();
        if (t != null) {
            app.ctx().tunnels.stop(t.id);
            app.status("隧道已停止: " + t.name);
        }
    }

    // ---------- frp 代理 ----------

    private FrpProxy selectedProxy() {
        int row = proxyTable.getSelectedRow();
        if (row < 0 || row >= proxyView.size()) return null;
        return proxyView.get(proxyTable.convertRowIndexToModel(row));
    }

    private void addProxy() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        FrpProxy x = FrpProxyDialog.show(app, null);
        if (x != null) {
            app.ctx().config.get().frpProxies.add(x);
            app.ctx().config.save();
            refresh();
        }
    }

    private void deleteProxy() {
        FrpProxy x = selectedProxy();
        if (x == null) {
            info("请先选择代理");
            return;
        }
        app.ctx().config.get().frpProxies.removeIf(v -> v.id.equals(x.id));
        app.ctx().config.save();
        refresh();
    }

    private void startFrpc() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        AppConfig.Settings st = app.ctx().config.get().settings;
        List<FrpProxy> proxies = app.ctx().config.proxiesOf(p.id);
        new Thread(() -> {
            try {
                app.ctx().frp.startFrpc(p.id, p, proxies, st);
                app.status("frpc 已启动");
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(app.frame(),
                        "frpc 启动失败: " + e.getMessage() + "\n（请确认已在设置中配置 frpc 路径，二进制已就位）",
                        "错误", JOptionPane.ERROR_MESSAGE));
            }
            SwingUtilities.invokeLater(this::refresh);
        }, "frpc-start").start();
    }

    private void stopFrpc() {
        SshProfile p = app.selectedProfile();
        if (p != null) {
            app.ctx().frp.stopFrpc(p.id);
            app.status("frpc 已停止");
            refresh();
        }
    }

    private void showFrpcLog() {
        SshProfile p = app.selectedProfile();
        if (p == null) return;
        List<String> log = app.ctx().frp.frpcLog(p.id, 200);
        showTextDialog("frpc 日志（最近 " + log.size() + " 行）", String.join("\n", log));
    }

    private void showProxyStatus() {
        SshProfile p = app.selectedProfile();
        if (p == null) return;
        int adminPort = app.ctx().config.get().settings.frpcAdminPort;
        new Thread(() -> {
            List<String[]> st = app.ctx().frp.frpcProxyStatus(p.id, adminPort);
            SwingUtilities.invokeLater(() -> {
                if (st.isEmpty()) {
                    info("无法获取代理状态（frpc 未运行或未开启 admin API）");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String[] row : st) sb.append(row[0]).append(" : ").append(row[1]).append("\n");
                    showTextDialog("frp 代理状态", sb.toString());
                }
            });
        }, "frp-status").start();
    }

    // ---------- frps ----------

    private void refreshFrps() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        AppConfig.Settings st = app.ctx().config.get().settings;
        SwingUtilities.invokeLater(() -> frpsLabel.setText(" frps 状态: 查询中..."));
        new Thread(() -> {
            try {
                var s = app.ctx().frp.remoteStatus(p.id, st);
                SwingUtilities.invokeLater(() -> frpsLabel.setText(" frps 状态: 二进制=" + (s.binaryExists() ? "已安装" : "未安装")
                        + "  systemd=" + (!s.unitExists() ? "未找到单元" : s.systemdState())));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> frpsLabel.setText(" frps 状态: 查询失败 (" + e.getMessage() + ")"));
            }
        }, "frps-status").start();
    }

    private void frpsAction(String action) {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        AppConfig.Settings st = app.ctx().config.get().settings;
        new Thread(() -> {
            try {
                String out = app.ctx().frp.remoteAction(p.id, st, action);
                SwingUtilities.invokeLater(() -> {
                    frpsLabel.setText(" frps 状态: " + out.replaceAll("\n", " | "));
                    info("systemctl " + action + " → " + out);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> info("操作失败: " + e.getMessage()));
            }
        }, "frps-action").start();
    }

    private void showFrpsLog() {
        SshProfile p = app.selectedProfile();
        if (p == null) return;
        AppConfig.Settings st = app.ctx().config.get().settings;
        new Thread(() -> {
            try {
                List<String> log = app.ctx().frp.remoteLog(p.id, st, 100);
                SwingUtilities.invokeLater(() -> showTextDialog("frps 日志", String.join("\n", log)));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> info("获取日志失败: " + e.getMessage()));
            }
        }, "frps-log").start();
    }

    /** 可视化编辑服务器上的 frps 配置文件，保存后同步设置与保险库，可选重启生效。 */
    private void showFrpsConfig() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        AppConfig.Settings st = app.ctx().config.get().settings;
        String path = st.frpsConfigPath;
        app.status("正在读取 frps 配置 ...");
        new Thread(() -> {
            try {
                String original = app.ctx().frp.readRemoteConfig(p.id, path);
                SwingUtilities.invokeLater(() -> {
                    FrpsConfigDialog.Result r = FrpsConfigDialog.show(app, path, original, st);
                    if (r == null) return;
                    app.status("正在写入 frps 配置 ...");
                    new Thread(() -> {
                        try {
                            app.ctx().frp.writeRemoteConfig(p.id, path, r.newText());
                            StringBuilder note = new StringBuilder();
                            if (r.changedBindPort() != null) {
                                st.frpsBindPort = r.changedBindPort();
                                app.ctx().config.save();
                                note.append("\n设置中的 frps 绑定端口已同步为 ").append(r.changedBindPort()).append("。");
                                SwingUtilities.invokeLater(app::refreshSettings);
                            }
                            if (r.changedToken() != null) {
                                try {
                                    app.ctx().vault.putSecret("frps.token", r.changedToken().isEmpty() ? null : r.changedToken());
                                    note.append("\nfrps token 已同步到本机保险库。");
                                } catch (Exception ve) {
                                    note.append("\n警告: token 同步保险库失败（").append(ve.getMessage())
                                            .append("），请用“设置 frps token”手动保存，否则 frpc 将无法认证。");
                                }
                            }
                            if (r.restart()) {
                                String out = app.ctx().frp.remoteAction(p.id, st, "restart");
                                note.append("\nfrps 已重启 → ").append(out.strip());
                            }
                            final String msg = "frps 配置已保存到 " + path + (note.isEmpty() ? "" : note);
                            SwingUtilities.invokeLater(() -> {
                                app.status("frps 配置已保存");
                                info(msg);
                                refreshFrps();
                            });
                        } catch (Exception e) {
                            SwingUtilities.invokeLater(() -> {
                                app.status("frps 配置保存失败");
                                info("保存失败: " + e.getMessage());
                            });
                        }
                    }, "frps-config-save").start();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    app.status("读取 frps 配置失败");
                    info("读取配置失败: " + e.getMessage());
                });
            }
        }, "frps-config-read").start();
    }

    /** 自动检测服务器上的 frps 并把命中项回填到设置。 */
    private void detectFrps() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        app.status("正在检测服务器上的 frps ...");
        new Thread(() -> {
            try {
                AppConfig.Settings st = app.ctx().config.get().settings;
                FrpManager.FrpsDetect d = app.ctx().frp.detectFrps(p.id);
                StringBuilder sb = new StringBuilder();
                boolean changed = false;
                if (d.binaryPath() != null) {
                    st.frpsRemotePath = d.binaryPath();
                    sb.append("frps 路径: ").append(d.binaryPath()).append("\n");
                    changed = true;
                } else {
                    sb.append("frps 路径: 未找到（可用“一键部署 frps…”上传）\n");
                }
                if (d.configPath() != null) {
                    st.frpsConfigPath = d.configPath();
                    sb.append("配置文件: ").append(d.configPath()).append("\n");
                    changed = true;
                } else {
                    sb.append("配置文件: 未找到\n");
                }
                sb.append("运行状态: ").append(d.runningPid() != null ? "运行中 (pid " + d.runningPid() + ")" : "未运行").append("\n");
                if (d.unitName() != null) {
                    st.frpsUnitName = d.unitName();
                    sb.append("systemd 单元: ").append(d.unitName()).append("\n");
                    changed = true;
                } else {
                    sb.append("systemd 单元: 未找到（可用“自动配置 systemd”生成）\n");
                }
                if (d.bindPort() != null) {
                    sb.append("bindPort: ").append(d.bindPort());
                    if (d.bindPort() != st.frpsBindPort) {
                        st.frpsBindPort = d.bindPort();
                        sb.append("（已更新设置）");
                    }
                    sb.append("\n");
                    changed = true;
                }
                if (changed) {
                    app.ctx().config.save();
                    SwingUtilities.invokeLater(app::refreshSettings);
                }
                SwingUtilities.invokeLater(() -> {
                    app.status("frps 检测完成");
                    showTextDialog("frps 自动检测结果", sb.toString());
                    refreshFrps();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    app.status("frps 检测失败");
                    info("检测失败: " + e.getMessage());
                });
            }
        }, "frps-detect").start();
    }

    /** 单元缺失时自动生成 systemd 配置；当前路径不可用时先自动检测回填。 */
    private void autoSetupSystemd() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        app.status("正在自动配置 frps systemd ...");
        new Thread(() -> {
            try {
                AppConfig.Settings st = app.ctx().config.get().settings;
                StringBuilder pre = new StringBuilder();
                if (!app.ctx().frp.remoteStatus(p.id, st).binaryExists()) {
                    FrpManager.FrpsDetect d = app.ctx().frp.detectFrps(p.id);
                    if (d.binaryPath() == null) {
                        SwingUtilities.invokeLater(() -> {
                            app.status("未找到 frps");
                            info("服务器上未找到 frps 二进制，请先“一键部署 frps…”上传。");
                        });
                        return;
                    }
                    st.frpsRemotePath = d.binaryPath();
                    if (d.configPath() != null) st.frpsConfigPath = d.configPath();
                    if (d.unitName() != null) st.frpsUnitName = d.unitName();
                    if (d.bindPort() != null) st.frpsBindPort = d.bindPort();
                    app.ctx().config.save();
                    SwingUtilities.invokeLater(app::refreshSettings);
                    pre.append("已自动检测并回填:\nfrps=").append(st.frpsRemotePath)
                            .append("\n配置=").append(st.frpsConfigPath).append("\n\n");
                }
                FrpManager.SystemdSetupResult r = app.ctx().frp.setupSystemdUnit(p.id, st, st.frpsRemotePath, st.frpsConfigPath);
                SwingUtilities.invokeLater(() -> {
                    app.status(r.created() ? "frps systemd 配置完成" : "frps systemd 单元已存在");
                    showTextDialog("自动配置 systemd", pre + r.message());
                    refreshFrps();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    app.status("自动配置失败");
                    info("自动配置失败: " + e.getMessage());
                });
            }
        }, "frps-setup").start();
    }

    private void deployFrps() {
        SshProfile p = app.selectedProfile();
        if (p == null) {
            info("请先在左侧选择服务器");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("选择本机 frps 二进制文件");
        if (fc.showOpenDialog(app.frame()) != JFileChooser.APPROVE_OPTION) return;
        File bin = fc.getSelectedFile();
        AppConfig.Settings st = app.ctx().config.get().settings;
        app.status("正在部署 frps ...");
        new Thread(() -> {
            try {
                String out = app.ctx().frp.deployFrps(p.id, st, bin.toPath());
                SwingUtilities.invokeLater(() -> {
                    app.status("frps 部署完成");
                    JOptionPane.showMessageDialog(app.frame(), "部署完成。\n" + out, "完成", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    app.status("frps 部署失败");
                    JOptionPane.showMessageDialog(app.frame(), "部署失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "frps-deploy").start();
    }

    private void setFrpsToken() {
        JPasswordField f = new JPasswordField();
        int r = JOptionPane.showConfirmDialog(app.frame(), f, "输入 frps auth token（存储于加密保险库）",
                JOptionPane.OK_CANCEL_OPTION);
        if (r != JOptionPane.OK_OPTION) return;
        try {
            app.ctx().vault.putSecret("frps.token", new String(f.getPassword()));
            app.status("frps token 已保存");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(app.frame(), "保存失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showTextDialog(String title, String text) {
        JTextArea area = new JTextArea(text, 24, 70);
        area.setEditable(false);
        area.setFont(Ui.consoleFont());
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(app.frame(), new JScrollPane(area), title, JOptionPane.PLAIN_MESSAGE);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(app.frame(), msg);
    }
}
