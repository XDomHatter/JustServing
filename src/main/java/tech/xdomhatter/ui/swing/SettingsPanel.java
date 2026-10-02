package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.store.AppPaths;

import javax.swing.*;
import java.awt.*;
import java.awt.Desktop;
import java.io.File;

public class SettingsPanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));

    private JSpinner interval, threads, cmdTimeout, frpsPort, adminPort;
    private JTextField sshPath, frpcPath, frpsPath, frpsUnit, frpsConf;
    private JCheckBox adminEnabled;

    public SettingsPanel(SwingApp app) {
        this.app = app;
        build();
    }

    public JPanel panel() {
        return panel;
    }

    /** 重新从配置填充控件；自动检测等外部更新设置后调用，避免设置页旧值在保存时覆盖新值。 */
    public void refresh() {
        var st = app.ctx().config.get().settings;
        interval.setValue(st.monitorIntervalMs / 1000);
        threads.setValue(st.transferThreads);
        cmdTimeout.setValue(st.commandTimeoutSec);
        sshPath.setText(st.sshPath);
        frpcPath.setText(st.frpcPath);
        frpsPath.setText(st.frpsRemotePath);
        frpsUnit.setText(st.frpsUnitName);
        frpsConf.setText(st.frpsConfigPath);
        frpsPort.setValue(st.frpsBindPort);
        adminPort.setValue(st.frpcAdminPort);
        adminEnabled.setSelected(st.frpcAdminEnabled);
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        var st = app.ctx().config.get().settings;

        // --- 外观 ---
        JComboBox<String> themeCombo = new JComboBox<>(new String[]{"跟随系统", "浅色", "深色"});
        themeCombo.setSelectedIndex(Ui.theme().ordinal());
        themeCombo.addActionListener(e ->
                Ui.setTheme(Ui.ThemeMode.values()[themeCombo.getSelectedIndex()]));
        JPanel appearance = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        appearance.add(Ui.section("外观"));
        appearance.add(new JLabel("主题:"));
        appearance.add(themeCombo);

        // --- 常规 / frp ---
        interval = new JSpinner(new SpinnerNumberModel(st.monitorIntervalMs / 1000, 1, 60, 1));
        threads = new JSpinner(new SpinnerNumberModel(st.transferThreads, 1, 8, 1));
        cmdTimeout = new JSpinner(new SpinnerNumberModel(st.commandTimeoutSec, 1, 86400, 1));
        sshPath = new JTextField(st.sshPath, 24);
        frpcPath = new JTextField(st.frpcPath, 24);
        JButton frpcBrowse = new JButton("浏览…");
        frpcBrowse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(app.frame()) == JFileChooser.APPROVE_OPTION) {
                frpcPath.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel frpcRow = new JPanel(new java.awt.BorderLayout(4, 0));
        frpcRow.add(frpcPath, BorderLayout.CENTER);
        frpcRow.add(frpcBrowse, BorderLayout.EAST);
        frpsPath = new JTextField(st.frpsRemotePath, 24);
        frpsUnit = new JTextField(st.frpsUnitName, 24);
        frpsConf = new JTextField(st.frpsConfigPath, 24);
        frpsPort = new JSpinner(new SpinnerNumberModel(st.frpsBindPort, 1, 65535, 1));
        adminPort = new JSpinner(new SpinnerNumberModel(st.frpcAdminPort, 1, 65535, 1));
        adminEnabled = new JCheckBox("启用 frpc admin API（用于查询代理状态）", st.frpcAdminEnabled);

        JPanel form = SwingUtil.form(
                new String[]{"监控刷新间隔（秒）:", "并发传输线程数:", "单次命令默认超时（秒）:", "原生 ssh 路径:",
                        "本机 frpc 路径:", "服务器 frps 路径:",
                        "frps systemd 单元名:", "frps 配置路径:", "frps 绑定端口:", "frpc admin 端口:", ""},
                new JComponent[]{interval, threads, cmdTimeout, sshPath, frpcRow, frpsPath, frpsUnit, frpsConf, frpsPort, adminPort, adminEnabled});

        JButton save = Ui.primary("保存设置", "check");
        save.addActionListener(e -> {
            st.monitorIntervalMs = (int) interval.getValue() * 1000;
            st.transferThreads = (int) threads.getValue();
            st.commandTimeoutSec = (int) cmdTimeout.getValue();
            st.sshPath = sshPath.getText().isBlank() ? "ssh" : sshPath.getText().strip();
            st.frpcPath = frpcPath.getText().strip();
            st.frpsRemotePath = frpsPath.getText().isBlank() ? "/usr/local/bin/frps" : frpsPath.getText().strip();
            st.frpsUnitName = frpsUnit.getText().isBlank() ? "frps" : frpsUnit.getText().strip();
            st.frpsConfigPath = frpsConf.getText().isBlank() ? "/etc/frp/frps.toml" : frpsConf.getText().strip();
            st.frpsBindPort = (int) frpsPort.getValue();
            st.frpcAdminPort = (int) adminPort.getValue();
            st.frpcAdminEnabled = adminEnabled.isSelected();
            app.ctx().config.save();
            app.ctx().monitor.setInterval(st.monitorIntervalMs);
            app.status("设置已保存");
        });

        JButton changePw = Ui.button("修改主密码", "edit");
        changePw.addActionListener(e -> changeMasterPassword());

        JButton openDir = Ui.button("打开配置目录", "folder");
        openDir.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(AppPaths.DIR.toFile());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(app.frame(), "打开失败: " + ex.getMessage());
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        actions.add(save);
        actions.add(changePw);
        actions.add(openDir);

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(form, BorderLayout.NORTH);
        top.add(actions, BorderLayout.SOUTH);
        top.setBorder(BorderFactory.createTitledBorder("常规设置 / frp"));

        JLabel about = Ui.hint(" 配置与数据目录: " + AppPaths.DIR + "    （凭据保存在 vault.json，AES-GCM + 主密码加密）");
        JPanel topBox = new JPanel();
        topBox.setLayout(new BoxLayout(topBox, BoxLayout.Y_AXIS));
        topBox.add(appearance);
        topBox.add(top);
        panel.add(topBox, BorderLayout.NORTH);
        panel.add(about, BorderLayout.SOUTH);
    }

    private void changeMasterPassword() {
        JPasswordField oldPw = new JPasswordField();
        JPasswordField newPw = new JPasswordField();
        JPasswordField newPw2 = new JPasswordField();
        JPanel p = SwingUtil.form(new String[]{"当前主密码:", "新主密码:", "再次输入新主密码:"},
                new JComponent[]{oldPw, newPw, newPw2});
        while (true) {
            int r = JOptionPane.showConfirmDialog(app.frame(), p, "修改主密码", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            char[] o = oldPw.getPassword();
            char[] n1 = newPw.getPassword();
            char[] n2 = newPw2.getPassword();
            if (n1.length == 0) {
                JOptionPane.showMessageDialog(app.frame(), "新主密码不能为空");
                continue;
            }
            if (!java.util.Arrays.equals(n1, n2)) {
                JOptionPane.showMessageDialog(app.frame(), "两次输入的新主密码不一致");
                continue;
            }
            try {
                app.ctx().vault.changeMasterPassword(o, n1);
                JOptionPane.showMessageDialog(app.frame(), "主密码修改成功，所有凭据已重新加密");
                app.status("主密码已修改");
                return;
            } catch (tech.xdomhatter.core.store.CredentialVault.WrongPasswordException e) {
                JOptionPane.showMessageDialog(app.frame(), "当前主密码错误");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(app.frame(), "修改失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
    }
}
