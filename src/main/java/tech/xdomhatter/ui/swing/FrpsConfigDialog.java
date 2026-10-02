package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.AppConfig;
import tech.xdomhatter.core.tunnel.FrpManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Map;

/**
 * frps 服务器配置的可视化编辑：表单覆盖常用字段，原始配置页可整体手改。
 * 保存时表单修改按行合并进原文件（保留注释与未涉及字段）；原始页一旦改动则以原文为准。
 */
public class FrpsConfigDialog {
    public record Result(String newText, String changedToken, Integer changedBindPort, boolean restart) {}

    private FrpsConfigDialog() {}

    public static Result show(SwingApp app, String path, String original, AppConfig.Settings st) {
        Map<String, String> cur = FrpManager.parseTomlFields(original);

        JTextField bindPort = new JTextField(8);
        bindPort.setText(cur.containsKey("bindPort") ? FrpManager.tomlUnquote(cur.get("bindPort"))
                : String.valueOf(st.frpsBindPort));
        JPasswordField token = new JPasswordField(18);
        token.setText(FrpManager.tomlUnquote(cur.getOrDefault("auth.token", "")));
        JTextField vhostHttp = new JTextField(8);
        vhostHttp.setText(FrpManager.tomlUnquote(cur.getOrDefault("vhostHTTPPort", "")));
        JTextField vhostHttps = new JTextField(8);
        vhostHttps.setText(FrpManager.tomlUnquote(cur.getOrDefault("vhostHTTPSPort", "")));
        JTextField subdomain = new JTextField(18);
        subdomain.setText(FrpManager.tomlUnquote(cur.getOrDefault("subdomainHost", "")));
        JTextField dashAddr = new JTextField(8);
        dashAddr.setText(FrpManager.tomlUnquote(cur.getOrDefault("webServer.addr", "")));
        JTextField dashPort = new JTextField(8);
        dashPort.setText(FrpManager.tomlUnquote(cur.getOrDefault("webServer.port", "")));
        JTextField dashUser = new JTextField(8);
        dashUser.setText(FrpManager.tomlUnquote(cur.getOrDefault("webServer.user", "")));
        JPasswordField dashPw = new JPasswordField(8);
        dashPw.setText(FrpManager.tomlUnquote(cur.getOrDefault("webServer.password", "")));

        JTextArea raw = new JTextArea(original, 18, 56);
        raw.setEditable(true);
        raw.setFont(Ui.monoFont());
        boolean[] rawTouched = {false};
        raw.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rawTouched[0] = true; }
            @Override public void removeUpdate(DocumentEvent e) { rawTouched[0] = true; }
            @Override public void changedUpdate(DocumentEvent e) { rawTouched[0] = true; }
        });

        JPanel form = SwingUtil.form(
                new String[]{"bindPort（必填）:", "auth.token:", "vhostHTTPPort:", "vhostHTTPSPort:", "subdomainHost:",
                        "仪表盘 addr:", "仪表盘 port:", "仪表盘 user:", "仪表盘 password:"},
                new JComponent[]{bindPort, token, vhostHttp, vhostHttps, subdomain, dashAddr, dashPort, dashUser, dashPw});
        JPanel formTab = new JPanel(new BorderLayout());
        formTab.add(form, BorderLayout.NORTH);
        formTab.add(Ui.hint(" 字段留空 = 删除该键；未修改的字段不会写入文件，注释与其它字段原样保留。"), BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("常用配置", formTab);
        tabs.addTab("原始配置", new JScrollPane(raw));
        tabs.setPreferredSize(new Dimension(620, 420));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(Ui.hint(" 服务器配置文件: " + path + "（保存前自动备份为 .bak）"), BorderLayout.NORTH);
        top.add(tabs, BorderLayout.CENTER);

        while (true) {
            Object[] options = {"保存", "保存并重启 frps", "取消"};
            int r = JOptionPane.showOptionDialog(app.frame(), top, "frps 配置文件",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (r != 0 && r != 1) return null;

            Integer bp = SwingUtil.parseInt(bindPort.getText().strip());
            if (bp == null || bp < 1 || bp > 65535) {
                JOptionPane.showMessageDialog(app.frame(), "bindPort 必须是 1-65535 的数字");
                continue;
            }
            if (!validPort(vhostHttp.getText().strip()) || !validPort(vhostHttps.getText().strip())
                    || !validPort(dashPort.getText().strip())) {
                JOptionPane.showMessageDialog(app.frame(), "端口必须是 1-65535 的数字，或留空删除");
                continue;
            }

            String newText;
            if (rawTouched[0]) {
                newText = raw.getText();
            } else {
                String tokText = new String(token.getPassword());
                FrpManager.FrpsEdits edits = new FrpManager.FrpsEdits(
                        changed(bindPort.getText().strip(), cur.get("bindPort")) ? String.valueOf(bp) : null,
                        changed(tokText, cur.get("auth.token")) ? tokText : null,
                        changed(vhostHttp.getText().strip(), cur.get("vhostHTTPPort")) ? vhostHttp.getText().strip() : null,
                        changed(vhostHttps.getText().strip(), cur.get("vhostHTTPSPort")) ? vhostHttps.getText().strip() : null,
                        changed(subdomain.getText().strip(), cur.get("subdomainHost")) ? subdomain.getText().strip() : null,
                        changed(dashAddr.getText().strip(), cur.get("webServer.addr")) ? dashAddr.getText().strip() : null,
                        changed(dashPort.getText().strip(), cur.get("webServer.port")) ? dashPort.getText().strip() : null,
                        changed(dashUser.getText().strip(), cur.get("webServer.user")) ? dashUser.getText().strip() : null,
                        changed(new String(dashPw.getPassword()), cur.get("webServer.password")) ? new String(dashPw.getPassword()) : null);
                newText = FrpManager.applyFrpsEdits(original, edits);
            }

            // 与原文件/st 对比，得出需要同步到设置与保险库的值
            Map<String, String> fin = FrpManager.parseTomlFields(newText);
            Integer finBind = SwingUtil.parseInt(FrpManager.tomlUnquote(fin.get("bindPort")));
            Integer changedBind = finBind != null && finBind != st.frpsBindPort ? finBind : null;
            String finToken = FrpManager.tomlUnquote(fin.getOrDefault("auth.token", ""));
            String changedToken = !finToken.equals(FrpManager.tomlUnquote(cur.getOrDefault("auth.token", ""))) ? finToken : null;
            return new Result(newText, changedToken, changedBind, r == 1);
        }
    }

    private static boolean changed(String field, String rawToml) {
        return !field.equals(FrpManager.tomlUnquote(rawToml));
    }

    private static boolean validPort(String s) {
        if (s.isEmpty()) return true;
        Integer v = SwingUtil.parseInt(s);
        return v != null && v >= 1 && v <= 65535;
    }
}
