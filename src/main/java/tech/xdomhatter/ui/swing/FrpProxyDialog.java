package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.FrpProxy;
import tech.xdomhatter.core.store.ConfigStore;

import javax.swing.*;

public class FrpProxyDialog {
    private FrpProxyDialog() {}

    public static FrpProxy show(SwingApp app, FrpProxy existing) {
        JTextField name = new JTextField();
        JComboBox<String> type = new JComboBox<>(new String[]{"tcp", "udp"});
        JTextField localIp = new JTextField("127.0.0.1");
        JTextField localPort = new JTextField();
        JTextField remotePort = new JTextField();
        JCheckBox enabled = new JCheckBox("启用", true);
        if (existing != null) {
            name.setText(existing.name);
            type.setSelectedItem(existing.type);
            localIp.setText(existing.localIp);
            localPort.setText(String.valueOf(existing.localPort));
            remotePort.setText(String.valueOf(existing.remotePort));
            enabled.setSelected(existing.enabled);
        }
        while (true) {
            JPanel p = SwingUtil.form(
                    new String[]{"代理名称:", "类型:", "本地 IP:", "本地端口:", "服务器远程端口:", ""},
                    new JComponent[]{name, type, localIp, localPort, remotePort, enabled});
            int r = JOptionPane.showConfirmDialog(app.frame(), p,
                    existing == null ? "新建 frp 代理" : "编辑 frp 代理", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            Integer lp = SwingUtil.parseInt(localPort.getText());
            Integer rp = SwingUtil.parseInt(remotePort.getText());
            if (lp == null || rp == null) {
                JOptionPane.showMessageDialog(app.frame(), "端口必须是数字");
                continue;
            }
            FrpProxy x = existing == null ? new FrpProxy() : existing;
            if (existing == null) {
                x.id = ConfigStore.newId();
                x.profileId = app.selectedProfile().id;
            }
            x.name = name.getText().strip();
            x.type = (String) type.getSelectedItem();
            x.localIp = localIp.getText().isBlank() ? "127.0.0.1" : localIp.getText().strip();
            x.localPort = lp;
            x.remotePort = rp;
            x.enabled = enabled.isSelected();
            return x;
        }
    }
}
