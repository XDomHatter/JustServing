package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.store.ConfigStore;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class ProfileDialog {
    private ProfileDialog() {}

    public static void show(SwingApp app, SshProfile existing) {
        JTextField name = new JTextField();
        JTextField host = new JTextField();
        JTextField port = new JTextField("22");
        JTextField user = new JTextField("root");
        JComboBox<String> auth = new JComboBox<>(new String[]{"密码", "私钥"});
        JPasswordField password = new JPasswordField();
        JTextField keyPath = new JTextField();
        JButton browse = new JButton("浏览…");
        JPasswordField keyPass = new JPasswordField();

        password.setToolTipText("密码输入（保存后写入加密保险库）");
        keyPass.setToolTipText("私钥口令（可留空）");

        if (existing != null) {
            name.setText(existing.name);
            host.setText(existing.host);
            port.setText(String.valueOf(existing.port));
            user.setText(existing.user);
            auth.setSelectedIndex(existing.authType == SshProfile.AuthType.KEY ? 1 : 0);
            keyPath.setText(existing.keyPath);
        }
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(app.frame()) == JFileChooser.APPROVE_OPTION) {
                keyPath.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel keyRow = new JPanel(new java.awt.BorderLayout(4, 0));
        keyRow.add(keyPath, BorderLayout.CENTER);
        keyRow.add(browse, BorderLayout.EAST);

        while (true) {
            JPanel p = SwingUtil.form(
                    new String[]{"名称:", "主机:", "端口:", "用户名:", "认证方式:", "密码（新输入则覆盖）:", "私钥路径:", "私钥口令（新输入则覆盖）:"},
                    new JComponent[]{name, host, port, user, auth, password, keyRow, keyPass});
            int r = JOptionPane.showConfirmDialog(app.frame(), p,
                    existing == null ? "新建连接" : "编辑连接", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            String h = host.getText().strip();
            Integer pt = SwingUtil.parseInt(port.getText());
            String u = user.getText().strip();
            if (h.isEmpty() || u.isEmpty() || pt == null) {
                JOptionPane.showMessageDialog(app.frame(), "主机、用户名、端口（1-65535）必填");
                continue;
            }
            SshProfile sp = existing == null ? new SshProfile() : existing;
            if (existing == null) sp.id = ConfigStore.newId();
            sp.name = name.getText().isBlank() ? h : name.getText().strip();
            sp.host = h;
            sp.port = pt;
            sp.user = u;
            sp.authType = auth.getSelectedIndex() == 1 ? SshProfile.AuthType.KEY : SshProfile.AuthType.PASSWORD;
            sp.keyPath = keyPath.getText().strip();
            try {
                if (!password.getText().isEmpty()) {
                    app.ctx().vault.putSecret(sp.secretKey(), password.getText());
                }
                if (!keyPass.getText().isEmpty()) {
                    app.ctx().vault.putSecret(sp.keyPassKey(), keyPass.getText());
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(app.frame(), "凭据保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                continue;
            }
            if (existing == null) {
                app.ctx().config.get().profiles.add(sp);
            }
            app.ctx().config.save();
            app.refreshProfiles();
            return;
        }
    }
}
