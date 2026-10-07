package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.apps.AppManager;
import tech.xdomhatter.core.model.ManagedApp;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.terminal.ShellHandle;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 企业服务管理：服务器端应用的部署（git/上传/服务器已有文件）、目录维护、一键启停、下载与前台调试。 */
public class AppPanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));

    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"应用", "方式", "状态", "部署目录", "来源", "更新时间"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = Ui.table(model);
    private List<ManagedApp> view = new ArrayList<>();
    private Map<String, AppManager.AppStatus> statuses = Map.of();
    private final JTextField filter = new JTextField();
    private final JLabel count = Ui.hint("");

    public AppPanel(SwingApp app) {
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
        JButton newBtn = Ui.primary("新建/部署", "plus");
        newBtn.addActionListener(e -> openDialog(null));
        JButton edit = Ui.button("编辑", "edit");
        edit.addActionListener(e -> openDialog(selected()));
        JButton update = Ui.button("更新", "upload");
        update.addActionListener(e -> updateSelected());
        JButton start = Ui.button("运行", "play");
        start.addActionListener(e -> runAction("start"));
        JButton stop = Ui.button("停止", "stop");
        stop.addActionListener(e -> runAction("stop"));
        JButton restart = Ui.button("重启", "refresh");
        restart.addActionListener(e -> runAction("restart"));
        JButton logBtn = Ui.button("日志", "file");
        logBtn.addActionListener(e -> showLog());
        JButton debug = Ui.button("前台调试", "terminal");
        debug.addActionListener(e -> debugSelected());
        JButton download = Ui.button("下载", "download");
        download.addActionListener(e -> downloadSelected());
        JButton del = Ui.danger("删除", "trash");
        del.addActionListener(e -> deleteSelected());

        filter.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "按名称/部署目录过滤");
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

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btns.add(refresh);
        btns.add(newBtn);
        btns.add(edit);
        btns.add(update);
        btns.add(start);
        btns.add(stop);
        btns.add(restart);
        btns.add(logBtn);
        btns.add(debug);
        btns.add(download);
        btns.add(del);

        Ui.width(table, 0, 140);
        Ui.width(table, 1, 80);
        Ui.width(table, 2, 150);
        Ui.width(table, 3, 260);
        Ui.width(table, 4, 110);
        Ui.mono(table, 3);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) openDialog(selected());
            }
        });

        JLabel hint = Ui.hint(" 说明：应用注册表保存在服务器 ~/.justserving/apps.json。后台进程模式无需 root、断开后继续运行；systemd 模式需 root、支持开机自启。前台调试在独立终端窗口运行，关闭窗口即结束应用。");
        JPanel north = new JPanel(new BorderLayout(8, 4));
        north.setOpaque(false);
        north.add(btns, BorderLayout.NORTH);
        north.add(filterBox, BorderLayout.CENTER);
        north.add(count, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(hint, BorderLayout.SOUTH);
    }

    private ManagedApp selected() {
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

    private void ensureConnected(SshProfile p) throws Exception {
        if (!app.ctx().ssh.isConnected(p.id)) app.ctx().ssh.connect(p);
    }

    /** 连接成功后自动刷新。 */
    public void onConnected(SshProfile p) {
        refresh();
    }

    public void refresh() {
        SshProfile p = requireProfile();
        if (p == null) return;
        app.status("正在获取应用列表: " + p.name + " ...");
        new SwingWorker<AppManager.Snapshot, Void>() {
            @Override
            protected AppManager.Snapshot doInBackground() throws Exception {
                ensureConnected(p);
                return app.ctx().apps.snapshot(p.id);
            }

            @Override
            protected void done() {
                try {
                    AppManager.Snapshot snap = get();
                    view = snap.apps();
                    statuses = snap.statuses();
                    refill();
                    app.status("已加载 " + view.size() + " 个应用 " + p.name);
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    app.status("获取应用列表失败");
                    JOptionPane.showMessageDialog(app.frame(),
                            "获取应用列表失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void refill() {
        String f = filter.getText().strip().toLowerCase();
        model.setRowCount(0);
        int shown = 0;
        for (ManagedApp a : view) {
            if (!f.isEmpty() && !(a.name.toLowerCase().contains(f) || a.deployDir.toLowerCase().contains(f))) continue;
            shown++;
            AppManager.AppStatus st = statuses.get(a.id);
            model.addRow(new Object[]{a.name, a.runModeText(), st == null ? "○ 未知" : st.text(),
                    a.deployDir, a.sourceText(), a.updatedAt});
        }
        count.setText(" 共 " + shown + " / " + view.size() + " 个应用");
    }

    // ---------- 新建 / 编辑 ----------

    private void openDialog(ManagedApp existing) {
        SshProfile p = requireProfile();
        if (p == null) return;
        AppDeployDialog.Result r = AppDeployDialog.show(app, existing);
        if (r == null) return;
        saveAndMaybeDeploy(p, r);
    }

    private void saveAndMaybeDeploy(SshProfile p, AppDeployDialog.Result r) {
        app.status("正在保存应用 " + r.app().name + " ...");
        String[] serverCheck = {null};
        new SwingWorker<Void, Void>() {
            Exception err;

            @Override
            protected Void doInBackground() throws Exception {
                ensureConnected(p);
                if (r.gitToken() != null && !r.gitToken().isBlank()) {
                    app.ctx().vault.putSecret(AppManager.gitTokenKey(r.app().id), r.gitToken());
                }
                ManagedApp saved = app.ctx().apps.saveApp(p.id, r.app());
                if (r.deployNow()) {
                    app.status("正在部署 " + saved.name + " ...");
                    if (saved.sourceType == ManagedApp.SourceType.GIT) {
                        app.ctx().apps.deployGit(p.id, saved, r.cleanBefore());
                    } else if (saved.sourceType == ManagedApp.SourceType.SERVER) {
                        // 不上传任何文件：仅确保目录存在；空目录提醒先放置应用文件
                        if (app.ctx().apps.deployExisting(p.id, saved).contains("@@EMPTY")) {
                            serverCheck[0] = "部署目录为空: " + saved.deployDir
                                    + "\n请先通过“文件”页或其他方式将应用文件放到该目录，再启动应用。";
                        }
                    } else if (r.uploadSource() != null) {
                        app.ctx().apps.deployUpload(p.id, saved, r.uploadSource(), r.cleanBefore());
                    }
                    app.status("正在执行部署命令 " + saved.name + " ...");
                    app.ctx().apps.runDeployCommand(p.id, saved);
                }
                if (r.startAfter()) {
                    app.status("正在启动 " + saved.name + " ...");
                    if (r.deployNow()) {
                        try {
                            app.ctx().apps.stop(p.id, saved);
                        } catch (Exception ignored) {
                        }
                    }
                    app.ctx().apps.start(p.id, saved, p.user);
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    app.status("应用 " + r.app().name + " 已保存"
                            + (r.deployNow() ? "并部署完成" : "")
                            + (r.startAfter() ? "，已启动" : ""));
                    app.toast((r.deployNow() ? (r.startAfter() ? "已部署并启动 " : "已部署 ")
                            : (r.startAfter() ? "已启动 " : "已保存 ")) + r.app().name);
                    if (serverCheck[0] != null) {
                        app.status("应用 " + r.app().name + " 已保存，但部署目录为空");
                        JOptionPane.showMessageDialog(app.frame(), serverCheck[0],
                                "服务器文件部署", JOptionPane.INFORMATION_MESSAGE);
                    }
                    refresh();
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    app.status("应用操作失败");
                    JOptionPane.showMessageDialog(app.frame(),
                            "应用 " + r.app().name + " 操作失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    refresh();
                }
            }
        }.execute();
    }

    // ---------- 运行 / 停止 / 重启 ----------

    private void runAction(String action) {
        ManagedApp a = selected();
        if (a == null) {
            JOptionPane.showMessageDialog(app.frame(), "请先选择应用", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SshProfile p = requireProfile();
        if (p == null) return;
        app.status(action + " " + a.name + " ...");
        new SwingWorker<AppManager.AppStatus, Void>() {
            @Override
            protected AppManager.AppStatus doInBackground() throws Exception {
                ensureConnected(p);
                return switch (action) {
                    case "start" -> app.ctx().apps.start(p.id, a, p.user);
                    case "stop" -> app.ctx().apps.stop(p.id, a);
                    default -> app.ctx().apps.restart(p.id, a, p.user);
                };
            }

            @Override
            protected void done() {
                try {
                    AppManager.AppStatus st = get();
                    app.status(a.name + " " + action + " 完成 — " + st.text());
                    app.toast(switch (action) {
                        case "start" -> "已启动 " + a.name;
                        case "stop" -> "已停止 " + a.name;
                        default -> "已重启 " + a.name;
                    });
                    refresh();
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    app.status(action + " " + a.name + " 失败");
                    JOptionPane.showMessageDialog(app.frame(),
                            a.name + " " + action + " 失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    refresh();
                }
            }
        }.execute();
    }

    // ---------- 更新部署 ----------

    private void updateSelected() {
        ManagedApp a = selected();
        if (a == null) {
            JOptionPane.showMessageDialog(app.frame(), "请先选择应用", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SshProfile p = requireProfile();
        if (p == null) return;
        if (a.sourceType == ManagedApp.SourceType.SERVER) {
            JOptionPane.showMessageDialog(app.frame(),
                    "“" + a.name + "”使用服务器文件部署，没有可拉取或上传的更新来源。\n"
                            + "如已手动更新了服务器上 " + a.deployDir + " 中的文件，直接“重启”即可生效。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (a.sourceType == ManagedApp.SourceType.GIT) {
            int r = JOptionPane.showConfirmDialog(app.frame(),
                    "从仓库拉取最新代码更新 “" + a.name + "”？", "确认更新", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            app.status("正在从仓库更新 " + a.name + " ...");
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    ensureConnected(p);
                    String out = app.ctx().apps.deployGit(p.id, a, false);
                    app.status("正在执行部署命令 " + a.name + " ...");
                    return appendDeployCommand(p.id, a, out);
                }

                @Override
                protected void done() {
                    try {
                        String out = get();
                        app.status("已更新 " + a.name);
                        // 配置了部署命令时展示其输出（构建日志有排查价值）；否则轻量气泡即可
                        if (a.deployCommand != null && !a.deployCommand.isBlank()) {
                            JOptionPane.showMessageDialog(app.frame(), out, "git 更新完成", JOptionPane.PLAIN_MESSAGE);
                        } else {
                            app.toast("已更新 " + a.name);
                        }
                        refresh();
                    } catch (Exception e) {
                        Throwable t = e.getCause() != null ? e.getCause() : e;
                        JOptionPane.showMessageDialog(app.frame(), "更新失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                        refresh();
                    }
                }
            }.execute();
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fc.setDialogTitle("选择要上传的 zip/文件/文件夹（zip 自动解压）");
        if (fc.showOpenDialog(app.frame()) != JFileChooser.APPROVE_OPTION) return;
        Path local = fc.getSelectedFile().toPath();
        boolean clean = JOptionPane.showConfirmDialog(app.frame(),
                "部署前是否清空部署目录 " + a.deployDir + " ？", "清空目录", JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
        if (clean && JOptionPane.showConfirmDialog(app.frame(), "确定清空 " + a.deployDir + " ？该操作不可恢复。",
                "二次确认", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        app.status("正在上传部署 " + a.name + " ...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                ensureConnected(p);
                String out = app.ctx().apps.deployUpload(p.id, a, local, clean);
                app.status("正在执行部署命令 " + a.name + " ...");
                return appendDeployCommand(p.id, a, out);
            }

            @Override
            protected void done() {
                try {
                    String out = get();
                    app.status("已部署 " + a.name);
                    // 配置了部署命令时展示其输出（构建日志有排查价值）；否则轻量气泡即可
                    if (a.deployCommand != null && !a.deployCommand.isBlank()) {
                        JOptionPane.showMessageDialog(app.frame(), out, "部署完成", JOptionPane.PLAIN_MESSAGE);
                    } else {
                        app.toast("已部署 " + a.name);
                    }
                    refresh();
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(app.frame(), "部署失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    refresh();
                }
            }
        }.execute();
    }

    /** 部署完成后执行部署命令（未配置则原样返回部署输出），输出分段追加供完成弹窗展示。 */
    private String appendDeployCommand(String profileId, ManagedApp a, String deployOut) throws Exception {
        String out = app.ctx().apps.runDeployCommand(profileId, a);
        return out.isEmpty() ? deployOut : deployOut + "\n──── 部署命令输出 ────\n" + out;
    }

    // ---------- 日志 / 调试 / 下载 / 删除 ----------

    private void showLog() {
        ManagedApp a = selected();
        if (a == null) return;
        SshProfile p = requireProfile();
        if (p == null) return;
        AppLogDialog.show(app, p, a);
    }

    private void debugSelected() {
        ManagedApp a = selected();
        if (a == null) return;
        SshProfile p = requireProfile();
        if (p == null) return;
        app.status("正在打开前台调试终端: " + a.name + " ...");
        new SwingWorker<ShellHandle, Void>() {
            @Override
            protected ShellHandle doInBackground() throws Exception {
                ensureConnected(p);
                return app.ctx().terminals.openExec(p.id, AppManager.debugCommand(a), 120, 32);
            }

            @Override
            protected void done() {
                try {
                    ShellHandle ch = get();
                    TerminalPanel.openChannelWindow(app.frame(), "调试 — " + a.name + "（关闭窗口即停止）", ch);
                    app.status("前台调试已打开（关闭窗口即停止应用）");
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(app.frame(), "打开调试终端失败: " + t.getMessage(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void downloadSelected() {
        ManagedApp a = selected();
        if (a == null) return;
        SshProfile p = requireProfile();
        if (p == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择本地保存目录（应用 → <目录>/" + a.name + "/app，额外数据 → extra/）");
        if (fc.showOpenDialog(app.frame()) != JFileChooser.APPROVE_OPTION) return;
        Path dir = fc.getSelectedFile().toPath();
        app.status("正在下载 " + a.name + " ...");
        new SwingWorker<AppManager.DownloadPlan, Void>() {
            @Override
            protected AppManager.DownloadPlan doInBackground() throws Exception {
                ensureConnected(p);
                return app.ctx().apps.download(p.id, a, dir);
            }

            @Override
                protected void done() {
                    try {
                        AppManager.DownloadPlan plan = get();
                        if (plan.skipped().isEmpty()) {
                            app.status("已开始下载 " + a.name + "（进度见传输页）");
                            app.toast("已开始下载 " + a.name + "（进度见传输页）");
                        } else {
                            app.status("已开始下载 " + a.name + "，部分额外数据路径不存在已跳过");
                            JTextArea area = new JTextArea(String.join("\n", plan.skipped()), 8, 50);
                            area.setEditable(false);
                            JOptionPane.showMessageDialog(app.frame(), new JScrollPane(area),
                                    "以下额外数据路径在服务器上不存在，已跳过", JOptionPane.WARNING_MESSAGE);
                        }
                    } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(app.frame(), "下载失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void deleteSelected() {
        ManagedApp a = selected();
        if (a == null) return;
        SshProfile p = requireProfile();
        if (p == null) return;
        JCheckBox removeFiles = new JCheckBox("同时删除服务器上的部署目录 " + a.deployDir);
        int r = JOptionPane.showConfirmDialog(app.frame(),
                new Object[]{"删除应用 “" + a.name + "”？", removeFiles},
                "确认删除", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        app.status("正在删除 " + a.name + " ...");
        new SwingWorker<Void, Void>() {
            @Override
                protected Void doInBackground() throws Exception {
                    ensureConnected(p);
                    app.ctx().apps.deleteApp(p.id, a.id, removeFiles.isSelected());
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        app.status("已删除 " + a.name);
                        app.toast("已删除 " + a.name);
                        refresh();
                    } catch (Exception e) {
                        Throwable t = e.getCause() != null ? e.getCause() : e;
                        JOptionPane.showMessageDialog(app.frame(), "删除失败: " + t.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    }
                }
        }.execute();
    }
}
