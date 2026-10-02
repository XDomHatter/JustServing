package tech.xdomhatter.ui.swing;

import tech.xdomhatter.core.model.ManagedApp;
import tech.xdomhatter.core.store.ConfigStore;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** 应用新建/编辑对话框：部署目录、启停命令、标准输入输出、环境变量、部署来源与额外数据路径。 */
public class AppDeployDialog {
    public record Result(ManagedApp app, String gitToken, Path uploadSource,
                         boolean deployNow, boolean cleanBefore, boolean startAfter) {}

    private AppDeployDialog() {}

    public static Result show(SwingApp app, ManagedApp existing) {
        JTextField name = new JTextField();
        JComboBox<String> runMode = new JComboBox<>(new String[]{
                "后台进程（无需 root，断开后继续运行）", "systemd（需 root，支持开机自启）"});
        JTextField deployDir = new JTextField();
        deployDir.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "~/apps/myapp（支持 ~ 前缀）");
        JTextField startCommand = new JTextField();
        startCommand.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "例: java -jar app.jar 或 ./bin/start.sh");
        JTextField stopCommand = new JTextField();
        stopCommand.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "可选；留空则按 PID 强制结束");
        JTextField stdin = new JTextField("/dev/null");
        JTextField stdout = new JTextField();
        stdout.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "留空 = ~/.justserving/logs/<id>.out.log");
        JTextField stderr = new JTextField();
        stderr.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "留空 = ~/.justserving/logs/<id>.err.log");
        JTextArea envArea = new JTextArea(3, 20);
        envArea.setFont(Ui.monoFont());
        JScrollPane envScroll = new JScrollPane(envArea);
        envScroll.setPreferredSize(new Dimension(220, 64));

        JRadioButton gitRadio = new JRadioButton("从 Git 仓库部署");
        JRadioButton uploadRadio = new JRadioButton("上传部署（zip 自动解压）");
        ButtonGroup sourceGroup = new ButtonGroup();
        sourceGroup.add(gitRadio);
        sourceGroup.add(uploadRadio);
        JTextField gitUrl = new JTextField();
        gitUrl.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "https://git.example.com/team/app.git");
        JTextField gitBranch = new JTextField();
        gitBranch.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "留空 = 默认分支");
        JComboBox<String> gitAuth = new JComboBox<>(new String[]{"匿名 / 使用服务器自身凭证", "HTTPS 令牌（存本机保险库）"});
        JPasswordField gitToken = new JPasswordField();
        gitToken.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "已保存则留空");
        JTextField uploadPath = new JTextField();
        JButton browse = new JButton("浏览…");
        JPanel uploadRow = new JPanel(new BorderLayout(4, 0));
        uploadRow.add(uploadPath, BorderLayout.CENTER);
        uploadRow.add(browse, BorderLayout.EAST);
        JTextArea extraArea = new JTextArea(2, 20);
        extraArea.setFont(Ui.monoFont());
        JScrollPane extraScroll = new JScrollPane(extraArea);
        extraScroll.setPreferredSize(new Dimension(220, 48));

        JCheckBox cleanBox = new JCheckBox("部署前清空部署目录（不可恢复）");
        JCheckBox startBox = new JCheckBox("完成后立即启动（运行中则重启）");
        JCheckBox autoStartBox = new JCheckBox("开机自启（systemctl enable）");

        if (existing != null) {
            name.setText(existing.name);
            runMode.setSelectedIndex(existing.runMode == ManagedApp.RunMode.SYSTEMD ? 1 : 0);
            deployDir.setText(existing.deployDir);
            startCommand.setText(existing.startCommand);
            stopCommand.setText(existing.stopCommand);
            stdin.setText(existing.stdinPath);
            stdout.setText(existing.stdoutPath);
            stderr.setText(existing.stderrPath);
            envArea.setText(String.join("\n", existing.env));
            extraArea.setText(String.join("\n", existing.extraPaths));
            if (existing.sourceType == ManagedApp.SourceType.GIT) gitRadio.setSelected(true);
            else uploadRadio.setSelected(true);
            gitUrl.setText(existing.gitUrl);
            gitBranch.setText(existing.gitBranch);
            gitAuth.setSelectedIndex("token".equals(existing.gitAuth) ? 1 : 0);
        } else {
            uploadRadio.setSelected(true);
        }

        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (fc.showOpenDialog(app.frame()) == JFileChooser.APPROVE_OPTION) {
                uploadPath.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        java.util.List<JComponent> gitFields = java.util.List.of(gitUrl, gitBranch, gitAuth, gitToken);
        Runnable sync = () -> {
            boolean git = gitRadio.isSelected();
            for (JComponent c : gitFields) c.setEnabled(git);
            uploadPath.setEnabled(!git);
            browse.setEnabled(!git);
        };
        gitRadio.addActionListener(e -> sync.run());
        uploadRadio.addActionListener(e -> sync.run());
        sync.run();

        JPanel sourceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sourceRow.setOpaque(false);
        sourceRow.add(gitRadio);
        sourceRow.add(uploadRadio);

        String[] labels = {"名称:", "运行方式:", "部署目录:", "启动命令:", "停止命令:",
                "标准输入(文件):", "标准输出(文件):", "标准错误(文件):", "环境变量(KEY=VALUE/行):",
                "部署来源:", "仓库地址:", "分支:", "认证方式:", "HTTPS 令牌:", "上传内容:",
                "额外数据路径(绝对路径/行):", "", "", ""};
        JComponent[] fields = {name, runMode, deployDir, startCommand, stopCommand,
                stdin, stdout, stderr, envScroll,
                sourceRow, gitUrl, gitBranch, gitAuth, gitToken, uploadRow,
                extraScroll, cleanBox, startBox, autoStartBox};
        JPanel form = SwingUtil.form(labels, fields);

        JScrollPane scroll = new JScrollPane(form);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(620, 640));

        AtomicReference<Result> out = new AtomicReference<>();
        JDialog dlg = new JDialog(app.frame(), existing == null ? "新建应用" : "编辑应用 — " + existing.name, true);
        JButton deployBtn = Ui.primary("保存并部署", "upload");
        JButton saveBtn = Ui.button("保存", "check");
        JButton cancelBtn = Ui.button("取消", "x");
        deployBtn.addActionListener(e -> {
            Result r = build(app, existing, name, runMode, deployDir, startCommand, stopCommand, stdin, stdout,
                    stderr, envArea, gitRadio, gitUrl, gitBranch, gitAuth, gitToken, uploadRadio, uploadPath,
                    extraArea, cleanBox, startBox, autoStartBox, true);
            if (r != null) {
                out.set(r);
                dlg.dispose();
            }
        });
        saveBtn.addActionListener(e -> {
            Result r = build(app, existing, name, runMode, deployDir, startCommand, stopCommand, stdin, stdout,
                    stderr, envArea, gitRadio, gitUrl, gitBranch, gitAuth, gitToken, uploadRadio, uploadPath,
                    extraArea, cleanBox, startBox, autoStartBox, false);
            if (r != null) {
                out.set(r);
                dlg.dispose();
            }
        });
        cancelBtn.addActionListener(e -> dlg.dispose());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        btns.add(deployBtn);
        btns.add(saveBtn);
        btns.add(cancelBtn);

        dlg.setLayout(new BorderLayout());
        dlg.add(scroll, BorderLayout.CENTER);
        dlg.add(btns, BorderLayout.SOUTH);
        dlg.getRootPane().setDefaultButton(deployBtn);
        dlg.pack();
        dlg.setLocationRelativeTo(app.frame());
        dlg.setVisible(true);
        return out.get();
    }

    private static Result build(SwingApp app, ManagedApp existing, JTextField name, JComboBox<String> runMode,
                                JTextField deployDir, JTextField startCommand, JTextField stopCommand,
                                JTextField stdin, JTextField stdout, JTextField stderr, JTextArea envArea,
                                JRadioButton gitRadio, JTextField gitUrl, JTextField gitBranch,
                                JComboBox<String> gitAuth, JPasswordField gitToken, JRadioButton uploadRadio,
                                JTextField uploadPath, JTextArea extraArea, JCheckBox cleanBox,
                                JCheckBox startBox, JCheckBox autoStartBox, boolean deployNow) {
        String nm = name.getText().strip();
        String dir = deployDir.getText().strip();
        String cmd = startCommand.getText().strip();
        if (nm.isEmpty() || dir.isEmpty() || cmd.isEmpty()) {
            JOptionPane.showMessageDialog(app.frame(), "名称、部署目录、启动命令必填", "校验失败", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        boolean git = gitRadio.isSelected();
        String url = gitUrl.getText().strip();
        boolean tokenAuth = git && gitAuth.getSelectedIndex() == 1;
        String token = tokenAuth ? new String(gitToken.getPassword()) : null;
        if (git && url.isEmpty()) {
            JOptionPane.showMessageDialog(app.frame(), "请填写 Git 仓库地址", "校验失败", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (tokenAuth && (token == null || token.isBlank())
                && (existing == null || !"token".equals(existing.gitAuth))) {
            JOptionPane.showMessageDialog(app.frame(), "请填写 HTTPS 令牌（保存后写入本机保险库，不会上传服务器）",
                    "校验失败", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        Path upload = !git && deployNow ? (uploadPath.getText().isBlank() ? null : Path.of(uploadPath.getText().strip())) : null;
        if (deployNow && !git && upload == null) {
            JOptionPane.showMessageDialog(app.frame(), "请选择要上传的 zip/文件/文件夹", "校验失败", JOptionPane.WARNING_MESSAGE);
            return null;
        }

        ManagedApp a = existing == null ? new ManagedApp() : existing;
        if (existing == null) a.id = ConfigStore.newId();
        a.name = nm;
        a.runMode = runMode.getSelectedIndex() == 1 ? ManagedApp.RunMode.SYSTEMD : ManagedApp.RunMode.DETACHED;
        a.deployDir = dir;
        a.startCommand = cmd;
        a.stopCommand = stopCommand.getText().strip();
        a.stdinPath = stdin.getText().isBlank() ? "/dev/null" : stdin.getText().strip();
        a.stdoutPath = stdout.getText().strip();
        a.stderrPath = stderr.getText().strip();
        a.env = new ArrayList<>();
        for (String line : envArea.getText().split("\r?\n")) {
            String t = line.strip();
            if (!t.isEmpty()) a.env.add(t);
        }
        a.sourceType = git ? ManagedApp.SourceType.GIT : ManagedApp.SourceType.UPLOAD;
        a.gitUrl = url;
        a.gitBranch = gitBranch.getText().strip();
        a.gitAuth = tokenAuth ? "token" : "none";
        a.autoStart = a.runMode == ManagedApp.RunMode.SYSTEMD && autoStartBox.isSelected();
        a.extraPaths = new ArrayList<>();
        for (String line : extraArea.getText().split("\r?\n")) {
            String t = line.strip();
            if (!t.isEmpty()) a.extraPaths.add(t);
        }
        return new Result(a, token, upload, deployNow, cleanBox.isSelected(), startBox.isSelected());
    }
}
