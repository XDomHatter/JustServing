package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.TunnelSpec;
import tech.xdomhatter.core.store.ConfigStore;

import javax.swing.*;

public class TunnelDialog {
    private TunnelDialog() {}

    /** 编辑 existing（原地修改）或新建；取消返回 null。 */
    public static TunnelSpec show(SwingApp app, TunnelSpec existing) {
        JTextField name = new JTextField();
        JComboBox<String> type = new JComboBox<>(new String[]{"远程转发 -R（内网穿透）", "本地转发 -L"});
        JTextField bind = new JTextField("0.0.0.0");
        JTextField listen = new JTextField();
        JTextField target = new JTextField("127.0.0.1");
        JTextField targetPort = new JTextField();
        JCheckBox auto = new JCheckBox("连接后自动启动");
        if (existing != null) {
            name.setText(existing.name);
            type.setSelectedIndex(existing.type == TunnelSpec.Type.REMOTE ? 0 : 1);
            bind.setText(existing.bindAddr);
            listen.setText(String.valueOf(existing.listenPort));
            target.setText(existing.targetHost);
            targetPort.setText(String.valueOf(existing.targetPort));
            auto.setSelected(existing.autoStart);
        }
        while (true) {
            JPanel p = SwingUtil.form(
                    new String[]{"名称:", "类型:", "绑定地址:", "监听端口:", "目标主机:", "目标端口:", ""},
                    new JComponent[]{name, type, bind, listen, target, targetPort, auto});
            int r = JOptionPane.showConfirmDialog(app.frame(), p,
                    existing == null ? "新建隧道" : "编辑隧道", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return null;
            Integer lp = SwingUtil.parseInt(listen.getText());
            Integer tp = SwingUtil.parseInt(targetPort.getText());
            if (lp == null || tp == null) {
                JOptionPane.showMessageDialog(app.frame(), "端口必须是数字");
                continue;
            }
            TunnelSpec t = existing == null ? new TunnelSpec() : existing;
            if (existing == null) {
                t.id = ConfigStore.newId();
                t.profileId = app.selectedProfile().id;
            }
            t.name = name.getText().strip();
            t.type = type.getSelectedIndex() == 0 ? TunnelSpec.Type.REMOTE : TunnelSpec.Type.LOCAL;
            t.bindAddr = bind.getText().isBlank() ? "0.0.0.0" : bind.getText().strip();
            t.listenPort = lp;
            t.targetHost = target.getText().isBlank() ? "127.0.0.1" : target.getText().strip();
            t.targetPort = tp;
            t.autoStart = auto.isSelected();
            return t;
        }
    }
}
