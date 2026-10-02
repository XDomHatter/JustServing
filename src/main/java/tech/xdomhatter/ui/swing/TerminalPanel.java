package tech.xdomhatter.ui.swing;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.terminal.ShellHandle;
import tech.xdomhatter.core.terminal.ShellTtyConnector;

import javax.swing.*;
import java.awt.*;

/** 内嵌交互式终端（JediTerm）：跟随连接生命周期，一机一会话。 */
public class TerminalPanel {
    private final SwingApp app;
    private final JPanel panel = new JPanel(new BorderLayout(8, 8));
    private final JPanel host = new JPanel(new BorderLayout()) {
        @Override
        public void updateUI() {
            super.updateUI();
            setBackground(Ui.isDark() ? new Color(0x1B1C1E) : Ui.c("Panel.background", Color.WHITE));
        }
    };
    private final JLabel state = Ui.hint("未连接");
    private final JLabel placeholder = Ui.hint(" 连接服务器后自动打开终端，或在左侧选中已连接的服务器后点「连接/重连」");

    private SshProfile current;
    private JediTermWidget term;

    public TerminalPanel(SwingApp app) {
        this.app = app;
        build();
    }

    public JPanel panel() {
        return panel;
    }

    private void build() {
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JButton open = Ui.button("连接/重连", "connect");
        open.addActionListener(e -> openTerminal(app.selectedProfile()));
        JButton close = Ui.button("断开终端", "disconnect");
        close.addActionListener(e -> closeTerminal());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        btns.add(open);
        btns.add(close);
        btns.add(state);

        panel.add(btns, BorderLayout.NORTH);
        host.add(placeholder, BorderLayout.CENTER);
        panel.add(host, BorderLayout.CENTER);
        panel.add(Ui.hint(" 说明：终端内所有按键（含 Ctrl-C）直接发给远端；窗口缩放自动同步远端 PTY；断开服务器连接会一并关闭其终端。"),
                BorderLayout.SOUTH);
    }

    /** 连接成功后自动打开该服务器的终端。 */
    public void onConnected(SshProfile p) {
        SwingUtilities.invokeLater(() -> openTerminal(p));
    }

    public void onDisconnected(String profileId) {
        SwingUtilities.invokeLater(() -> {
            if (current != null && current.id.equals(profileId)) closeTerminal();
        });
    }

    private void openTerminal(SshProfile p) {
        if (p == null) {
            JOptionPane.showMessageDialog(app.frame(), "请先在左侧选择服务器", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        closeTerminal();
        current = p;
        state.setText("正在打开终端: " + p.name + " ...");
        new SwingWorker<ShellHandle, Void>() {
            @Override
            protected ShellHandle doInBackground() throws Exception {
                return app.ctx().terminals.open(p.id, 120, 32);
            }

            @Override
            protected void done() {
                try {
                    ShellHandle ch = get();
                    // 注意：JediTermWidget 的 TermSize 构造器会触发 JDK 25 javac 内部错误，必须用 (cols, rows) 构造器
                    ThemedTerm w = new ThemedTerm(120, 32, new TermSettings());
                    w.setTtyConnector(new ShellTtyConnector(ch));
                    host.removeAll();
                    host.add(w, BorderLayout.CENTER);
                    host.revalidate();
                    host.repaint();
                    term = w;
                    w.start();
                    w.requestFocusInWindow();
                    state.setText("● 终端已连接 " + p.name);
                } catch (Exception e) {
                    Throwable t = e.getCause() != null ? e.getCause() : e;
                    state.setText("✗ 终端打开失败: " + t.getMessage());
                }
            }
        }.execute();
    }

    /** 在独立窗口中打开已建好的通道作为交互终端（应用前台调试用）；关闭窗口即结束远端进程。 */
    public static void openChannelWindow(JFrame owner, String title, ShellHandle ch) {
        JDialog dlg = new JDialog(owner, title, false);
        dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        ThemedTerm w = new ThemedTerm(120, 32, new TermSettings());
        w.setTtyConnector(new ShellTtyConnector(ch));
        dlg.add(w);
        dlg.setSize(1000, 640);
        dlg.setLocationRelativeTo(owner);
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                w.close();
                ch.close();
            }
        });
        dlg.setVisible(true);
        w.start();
        w.requestFocusInWindow();
    }

    private void closeTerminal() {
        if (term != null) {
            term.close();
            term = null;
        }
        host.removeAll();
        host.add(placeholder, BorderLayout.CENTER);
        host.revalidate();
        host.repaint();
        current = null;
        state.setText("未连接");
    }

    /** 终端外观：等宽字体；深色主题下用深色配色，浅色主题用 JediTerm 默认。主题实时读取，切换后重绘即生效。 */
    private static final class TermSettings extends DefaultSettingsProvider {
        private static final ColorPalette DARK = new DarkPalette();
        private static final TerminalColor DARK_FG = TerminalColor.color(new com.jediterm.core.Color(0xD4D7DC));
        private static final TerminalColor DARK_BG = TerminalColor.color(new com.jediterm.core.Color(0x1B1C1E));

        @Override
        public Font getTerminalFont() {
            return Ui.monoFont();
        }

        @Override
        public float getTerminalFontSize() {
            return 13f;
        }

        @Override
        public ColorPalette getTerminalColorPalette() {
            return Ui.isDark() ? DARK : super.getTerminalColorPalette();
        }

        /** 仅在 widget 构造时读取，且不会回落到 getDefaultForeground/Background：必须在此返回 RGB 默认样式，
         *  否则 StyleState 默认为索引 0/15，终端复位（vim/less/clear 等）后经调色板渲染成白底黑字块。 */
        @Override
        public TextStyle getDefaultStyle() {
            return Ui.isDark() ? new TextStyle(DARK_FG, DARK_BG) : super.getDefaultStyle();
        }

        @Override
        public TerminalColor getDefaultBackground() {
            return Ui.isDark() ? DARK_BG : super.getDefaultBackground();
        }

        @Override
        public TerminalColor getDefaultForeground() {
            return Ui.isDark() ? DARK_FG : super.getDefaultForeground();
        }
    }

    /** 持有 StyleState 引用，主题热切换时同步默认样式（复位/反显/光标块颜色由此派生）。 */
    private static final class ThemedTerm extends JediTermWidget {
        private final SettingsProvider provider;
        private StyleState styleState;

        ThemedTerm(int cols, int rows, SettingsProvider provider) {
            super(cols, rows, provider);
            this.provider = provider;
        }

        @Override
        protected StyleState createDefaultStyle() {
            StyleState s = super.createDefaultStyle();
            styleState = s;
            return s;
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (provider != null && styleState != null) {
                styleState.setDefaultStyle(provider.getDefaultStyle());
                repaint();
            }
        }
    }

    /** 深色 ANSI 16 色（背景索引 0 映射为主题底色）。 */
    private static final class DarkPalette extends ColorPalette {
        private static final int[] ANSI = {
                0x1B1D21, 0xE5484D, 0x46A758, 0xD49B00, 0x3E7BFA, 0xB083F0, 0x00B4C8, 0xB0B4BA,
                0x5A5F6B, 0xFF6369, 0x63D26C, 0xFFB224, 0x74A4FC, 0xC894F9, 0x4FCCDD, 0xE9ECEF};

        @Override
        protected com.jediterm.core.Color getForegroundByColorIndex(int i) {
            return new com.jediterm.core.Color(ANSI[i & 15]);
        }

        @Override
        protected com.jediterm.core.Color getBackgroundByColorIndex(int i) {
            return new com.jediterm.core.Color(i == 0 ? 0x1B1C1E : ANSI[i & 15]);
        }
    }
}
