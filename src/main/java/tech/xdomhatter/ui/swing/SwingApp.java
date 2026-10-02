package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.AppContext;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.model.TunnelSpec;
import tech.xdomhatter.core.store.CredentialVault;

import javax.swing.*;
import java.awt.*;

public class SwingApp {
    private final AppContext ctx;
    private JFrame frame;
    private DefaultListModel<SshProfile> profileModel;
    private JList<SshProfile> profileList;
    private JScrollPane listScroll;
    private JLabel statusLabel;
    private FilePanel filePanel;
    private MonitorPanel monitorPanel;
    private TunnelPanel tunnelPanel;
    private CommandPanel commandPanel;
    private ServicePanel servicePanel;
    private AppPanel appPanel;
    private TerminalPanel terminalPanel;
    private SettingsPanel settingsPanel;

    public SwingApp(AppContext ctx) {
        this.ctx = ctx;
    }

    public static void run(AppContext ctx) {
        Ui.install();
        SwingUtilities.invokeLater(() -> {
            SwingApp app = new SwingApp(ctx);
            if (!app.ensureVault()) {
                System.exit(0);
            }
            app.show();
        });
    }

    private boolean ensureVault() {
        String error = null;
        if (!ctx.vault.isInitialized()) {
            while (true) {
                char[] pw = VaultDialog.setMasterPassword(error);
                if (pw == null) return false;
                try {
                    ctx.vault.initialize(pw);
                    return true;
                } catch (Exception ex) {
                    error = "初始化失败: " + ex.getMessage();
                }
            }
        }
        while (true) {
            char[] pw = VaultDialog.unlock(error);
            if (pw == null) return false;
            try {
                ctx.vault.unlock(pw);
                return true;
            } catch (CredentialVault.WrongPasswordException e) {
                error = "主密码错误，请重试";
            } catch (Exception ex) {
                error = "解锁失败: " + ex.getMessage();
            }
        }
    }

    private void show() {
        frame = new JFrame("JustServing — 服务器管理");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImage(Ui.appIcon());

        profileModel = new DefaultListModel<>();
        profileList = new JList<>(profileModel);
        profileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean foc) {
                Component c = super.getListCellRendererComponent(list, value, index, sel, foc);
                if (value instanceof SshProfile sp) {
                    boolean connected = ctx.ssh.isConnected(sp.id);
                    Color dim = Ui.dim();
                    setText("<html><b>" + Ui.esc(sp.name) + "</b>&nbsp;&nbsp;<small><font color=" + Ui.hex(dim)
                            + ">" + Ui.esc(sp.user) + "@" + Ui.esc(sp.host) + ":" + sp.port + "</font>&nbsp;"
                            + "<font color=" + (connected ? Ui.hex(Ui.OK) : Ui.hex(dim)) + ">"
                            + (connected ? "● 已连接" : "○ 未连接") + "</font></small></html>");
                }
                setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 6));
                return c;
            }
        });
        refreshProfiles();

        JPanel sidebar = new JPanel(new BorderLayout(8, 8)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Ui.sidebarBg());
                if (profileList != null) profileList.setBackground(Ui.sidebarBg());
                if (listScroll != null) listScroll.getViewport().setBackground(Ui.sidebarBg());
            }
        };
        sidebar.setBackground(Ui.sidebarBg());
        profileList.setBackground(Ui.sidebarBg());

        JButton connectBtn = Ui.primary("连接", "connect");
        connectBtn.addActionListener(e -> connectSelected());
        JButton discBtn = Ui.tool("断开", "disconnect");
        discBtn.addActionListener(e -> disconnectSelected());
        JButton newBtn = Ui.tool("新建", "plus");
        newBtn.addActionListener(e -> ProfileDialog.show(this, null));
        JButton editBtn = Ui.tool("编辑", "edit");
        editBtn.addActionListener(e -> ProfileDialog.show(this, selectedProfile()));
        JButton delBtn = Ui.tool("删除", "trash");
        delBtn.addActionListener(e -> deleteSelected());
        JButton refreshBtn = Ui.tool("刷新", "refresh");
        refreshBtn.addActionListener(e -> refreshProfiles());

        JPanel tools = new JPanel(new GridLayout(0, 2, 4, 2));
        tools.setOpaque(false);
        tools.add(discBtn);
        tools.add(newBtn);
        tools.add(editBtn);
        tools.add(delBtn);
        tools.add(refreshBtn);

        JPanel north = new JPanel(new BorderLayout(0, 8));
        north.setOpaque(false);
        north.add(Ui.section("服务器"), BorderLayout.NORTH);
        north.add(connectBtn, BorderLayout.CENTER);
        north.add(tools, BorderLayout.SOUTH);

        listScroll = new JScrollPane(profileList);
        listScroll.setBorder(null);
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);

        sidebar.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        sidebar.add(north, BorderLayout.NORTH);
        sidebar.add(listScroll, BorderLayout.CENTER);
        sidebar.setPreferredSize(new Dimension(300, 0));

        JTabbedPane tabs = new JTabbedPane();
        filePanel = new FilePanel(this);
        monitorPanel = new MonitorPanel(this);
        tunnelPanel = new TunnelPanel(this);
        commandPanel = new CommandPanel(this);
        servicePanel = new ServicePanel(this);
        appPanel = new AppPanel(this);
        terminalPanel = new TerminalPanel(this);
        TransfersPanel transfersPanel = new TransfersPanel(this);
        OpenWithPanel openWithPanel = new OpenWithPanel(this);
        SettingsPanel settingsPanel = new SettingsPanel(this);
        this.settingsPanel = settingsPanel;
        tabs.addTab("文件", Ui.icon("folder"), filePanel.panel());
        tabs.addTab("监控", Ui.icon("chart"), monitorPanel.panel());
        tabs.addTab("隧道", Ui.icon("tunnel"), tunnelPanel.panel());
        tabs.addTab("命令", Ui.icon("zap"), commandPanel.panel());
        tabs.addTab("服务", Ui.icon("connect"), servicePanel.panel());
        tabs.addTab("应用", Ui.icon("play"), appPanel.panel());
        tabs.addTab("终端", Ui.icon("terminal"), terminalPanel.panel());
        tabs.addTab("传输", Ui.icon("transfer"), transfersPanel.panel());
        tabs.addTab("打开方式", Ui.icon("zap"), openWithPanel.panel());
        tabs.addTab("设置", Ui.icon("sliders"), settingsPanel.panel());

        JPanel statusBar = new JPanel(new BorderLayout(10, 0));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.c("Component.borderColor", Color.GRAY)),
                BorderFactory.createEmptyBorder(5, 14, 5, 10)));
        statusLabel = new JLabel("就绪");
        JButton themeBtn = Ui.tool("", Ui.isDark() ? "sun" : "moon");
        themeBtn.setToolTipText("切换浅色/深色主题");
        themeBtn.addActionListener(e -> {
            Ui.setTheme(Ui.isDark() ? Ui.ThemeMode.LIGHT : Ui.ThemeMode.DARK);
            themeBtn.setIcon(Ui.icon(Ui.isDark() ? "sun" : "moon"));
        });
        JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        statusRight.setOpaque(false);
        statusRight.add(themeBtn);
        statusRight.add(Ui.hint("v" + Ui.VERSION));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        statusBar.add(statusRight, BorderLayout.EAST);

        frame.add(sidebar, BorderLayout.WEST);
        frame.add(tabs, BorderLayout.CENTER);
        frame.add(statusBar, BorderLayout.SOUTH);
        Ui.rememberGeometry(frame);
        frame.setVisible(true);
    }

    public AppContext ctx() {
        return ctx;
    }

    public JFrame frame() {
        return frame;
    }

    public SshProfile selectedProfile() {
        return profileList.getSelectedValue();
    }

    public SshProfile profileById(String id) {
        return ctx.config.profile(id).orElse(null);
    }

    /** 设置被程序化更新（如 frps 自动检测回填）后刷新设置页控件，避免旧值在保存时覆盖。 */
    public void refreshSettings() {
        if (settingsPanel != null) settingsPanel.refresh();
    }

    public void refreshProfiles() {
        SwingUtilities.invokeLater(() -> {
            SshProfile sel = profileList.getSelectedValue();
            profileModel.clear();
            for (SshProfile p : ctx.config.get().profiles) profileModel.addElement(p);
            if (sel != null) profileList.setSelectedValue(sel, true);
            profileList.repaint();
        });
        if (commandPanel != null) commandPanel.refreshAll();
    }

    public void status(String s) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(s));
    }

    private void deleteSelected() {
        SshProfile p = selectedProfile();
        if (p == null) return;
        int r = JOptionPane.showConfirmDialog(frame,
                "删除连接 “" + p.name + "”？相关隧道和 frp 代理也会一并删除。",
                "确认删除", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        ctx.config.removeProfile(p.id);
        ctx.vault.removeSecret(p.secretKey());
        ctx.vault.removeSecret(p.keyPassKey());
        refreshProfiles();
        tunnelPanel.refresh();
        status("已删除 " + p.name);
    }

    public void connectSelected() {
        SshProfile p = selectedProfile();
        if (p == null || ctx.ssh.isConnected(p.id)) return;
        status("连接中: " + p.name + " ...");
        new SwingWorker<Void, Void>() {
            Exception err;

            @Override
            protected Void doInBackground() {
                try {
                    ctx.ssh.connect(p);
                } catch (Exception e) {
                    err = e;
                }
                return null;
            }

            @Override
            protected void done() {
                if (err != null) {
                    status("连接失败: " + err.getMessage());
                    JOptionPane.showMessageDialog(frame, "连接失败: " + err.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                } else {
                    status("已连接 " + p.user + "@" + p.host);
                    for (TunnelSpec t : ctx.config.tunnelsOf(p.id)) {
                        if (t.autoStart) {
                            try {
                                ctx.tunnels.start(t);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    filePanel.onConnected(p);
                    monitorPanel.onConnected(p);
                    terminalPanel.onConnected(p);
                    appPanel.onConnected(p);
                }
                refreshProfiles();
                tunnelPanel.refresh();
            }
        }.execute();
    }

    public void disconnectSelected() {
        SshProfile p = selectedProfile();
        if (p == null) return;
        ctx.monitor.stop(p.id);
        ctx.tunnels.stopProfile(p.id);
        ctx.sftp.close(p.id);
        ctx.ssh.disconnect(p.id);
        filePanel.onDisconnected(p.id);
        monitorPanel.onDisconnected(p.id);
        terminalPanel.onDisconnected(p.id);
        tunnelPanel.refresh();
        refreshProfiles();
        status("已断开 " + p.name);
    }
}
