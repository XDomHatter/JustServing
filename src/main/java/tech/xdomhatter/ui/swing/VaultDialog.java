package tech.xdomhatter.ui.swing;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/** 主密码设置/解锁对话框：品牌标题 + 内嵌错误提示，替代原 JOptionPane 流程。 */
final class VaultDialog {
    private VaultDialog() {}

    /** 首次使用：设置主密码（对话框内校验两次输入）。返回 null 表示取消。 */
    static char[] setMasterPassword(String error) {
        return show(true, error);
    }

    /** 解锁：输入主密码。返回 null 表示取消。 */
    static char[] unlock(String error) {
        return show(false, error);
    }

    private static char[] show(boolean first, String error) {
        JPasswordField f1 = new JPasswordField(20);
        JPasswordField f2 = first ? new JPasswordField(20) : null;
        char[][] result = new char[1][];

        JDialog d = new JDialog((Frame) null, first ? "设置主密码" : "解锁 JustServing", true);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));

        JPanel head = new JPanel(new GridLayout(0, 1, 3, 3));
        JLabel title = new JLabel(first ? "欢迎使用 JustServing" : "JustServing 已锁定");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 4f));
        JLabel desc = new JLabel(first ? "首次使用，请设置主密码（用于加密 SSH 凭据）" : "请输入主密码以解锁凭据");
        desc.setForeground(Ui.dim());
        head.add(title);
        head.add(desc);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 0, 4, 8);
        g.anchor = GridBagConstraints.WEST;
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 0;
        g.fill = GridBagConstraints.NONE;
        form.add(new JLabel("主密码:"), g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        form.add(f1, g);
        if (first) {
            g.gridx = 0;
            g.gridy = 1;
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            form.add(new JLabel("再次输入:"), g);
            g.gridx = 1;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            form.add(f2, g);
        }

        JLabel err = new JLabel(error == null ? " " : error);
        err.setForeground(Ui.DANGER);

        JButton ok = Ui.primary(first ? "开始使用" : "解锁", "connect");
        JButton cancel = Ui.button("取消", null);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btns.add(cancel);
        btns.add(ok);

        Runnable confirm = () -> {
            char[] a = f1.getPassword();
            if (a.length == 0) {
                err.setText(first ? "主密码不能为空" : "请输入主密码");
                return;
            }
            if (first && !Arrays.equals(a, f2.getPassword())) {
                err.setText("两次输入不一致");
                return;
            }
            result[0] = a;
            d.dispose();
        };
        ok.addActionListener(e -> confirm.run());
        cancel.addActionListener(e -> d.dispose());

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(form, BorderLayout.NORTH);
        center.add(err, BorderLayout.CENTER);
        root.add(head, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(btns, BorderLayout.SOUTH);

        d.setContentPane(root);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        d.setSize(Math.max(d.getWidth(), 420), d.getHeight());
        d.setLocationRelativeTo(null);
        d.setVisible(true);
        return result[0];
    }
}
