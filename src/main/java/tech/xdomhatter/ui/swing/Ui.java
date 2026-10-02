package tech.xdomhatter.ui.swing;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.prefs.Preferences;

/** Swing 层统一设计系统：主题、字体、图标、组件样式。业务逻辑不在此处。 */
public final class Ui {
    public enum ThemeMode { SYSTEM, LIGHT, DARK }

    public static final String VERSION = "1.0.0";

    public static final Color ACCENT = new Color(0x3E7BFA);
    public static final Color OK = new Color(0x2F9E44);
    public static final Color WARN = new Color(0xE8890C);
    public static final Color DANGER = new Color(0xE5484D);

    private static final String PRIMARY_STYLE =
            "arc: 10; borderWidth: 0; background: #3E7BFA; foreground: #FFFFFF; "
                    + "hoverBackground: #2F6AE0; pressedBackground: #2557BD; focusedBackground: #3E7BFA; "
                    + "focusedBorderColor: #3E7BFA; disabledBackground: #3E7BFA";
    private static final String DANGER_STYLE =
            "arc: 10; borderWidth: 0; background: #E5484D; foreground: #FFFFFF; "
                    + "hoverBackground: #C93A3F; pressedBackground: #B03036; focusedBackground: #E5484D";

    private static ThemeMode mode = loadMode();
    private static Boolean systemDarkCache;
    private static Font mono;

    private Ui() {}

    // ---------- 主题 ----------

    public static void install() {
        try {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.boldMetal", "false");
        } catch (Exception ignored) {
        }
        applyLaf();
        tweakDefaults();
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
    }

    public static ThemeMode theme() {
        return mode;
    }

    public static void setTheme(ThemeMode m) {
        if (m == null) return;
        mode = m;
        prefs().put("theme", m.name());
        applyLaf();
        tweakDefaults();
        FlatLaf.updateUI();
    }

    public static boolean isDark() {
        return effective() == ThemeMode.DARK;
    }

    private static ThemeMode effective() {
        if (mode == ThemeMode.SYSTEM) return systemDark() ? ThemeMode.DARK : ThemeMode.LIGHT;
        return mode;
    }

    private static ThemeMode loadMode() {
        try {
            return ThemeMode.valueOf(prefs().get("theme", ThemeMode.SYSTEM.name()));
        } catch (Exception e) {
            return ThemeMode.SYSTEM;
        }
    }

    /** Windows 通过注册表读取系统深色模式；其他平台默认浅色。 */
    private static boolean systemDark() {
        if (!SystemInfo.isWindows) return false;
        if (systemDarkCache == null) {
            try {
                Process p = new ProcessBuilder("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                systemDarkCache = out.replace("\r", "").replace("\n", " ").trim().endsWith("0x0");
            } catch (Exception e) {
                systemDarkCache = Boolean.FALSE;
            }
        }
        return systemDarkCache;
    }

    private static void applyLaf() {
        try {
            UIManager.setLookAndFeel(effective() == ThemeMode.DARK ? new FlatDarkLaf() : new FlatLightLaf());
        } catch (Exception ignored) {
        }
    }

    private static void tweakDefaults() {
        if (SystemInfo.isWindows) {
            UIManager.put("defaultFont", new Font("Microsoft YaHei UI", Font.PLAIN, 13));
        }
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Table.rowHeight", 26);
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("MenuItem.arc", 8);
    }

    // ---------- 取色 ----------

    /** 从当前主题取 UI 默认颜色，供自绘组件使用，保证浅/深主题自动适配。 */
    public static Color c(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    public static Color dim() {
        return c("Label.disabledForeground", new Color(0x8A8F98));
    }

    public static Color sidebarBg() {
        Color c = UIManager.getColor("Sidebar.background");
        return c != null ? c : c("Panel.background", Color.WHITE);
    }

    public static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    // ---------- 图标 ----------

    private static final FlatSVGIcon.ColorFilter TINT = new FlatSVGIcon.ColorFilter(
            (java.util.function.Function<Color, Color>) color -> {
                Color c = UIManager.getColor("Label.foreground");
                return c != null ? c : Color.GRAY;
            });

    /** 加载 resources/icons/<name>.svg 并跟随前景色（随主题自动适配）。 */
    public static Icon icon(String name) {
        return icon(name, 16);
    }

    public static Icon icon(String name, int size) {
        FlatSVGIcon i = new FlatSVGIcon("icons/" + name + ".svg", size, size, Ui.class.getClassLoader());
        i.setColorFilter(TINT);
        return i;
    }

    /** 应用窗口图标：圆角渐变方块 + 白色闪电。 */
    public static Image appIcon() {
        int s = 64;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x4A8DF8), s, s, new Color(0x2F5FD0)));
        g.fillRoundRect(2, 2, s - 4, s - 4, 16, 16);
        g.setColor(Color.WHITE);
        g.fillPolygon(new int[]{37, 15, 30, 27, 49, 34}, new int[]{7, 36, 36, 57, 28, 28}, 6);
        g.dispose();
        return img;
    }

    // ---------- 组件工厂 ----------

    private static JButton base(String text, String iconName) {
        JButton b = new JButton(text);
        if (iconName != null) b.setIcon(icon(iconName));
        b.setFocusPainted(false);
        return b;
    }

    public static JButton button(String text, String iconName) {
        return base(text, iconName);
    }

    /** accent 填充主按钮。 */
    public static JButton primary(String text, String iconName) {
        JButton b = base(text, iconName);
        b.putClientProperty(FlatClientProperties.STYLE, PRIMARY_STYLE);
        return b;
    }

    /** 危险操作按钮。 */
    public static JButton danger(String text, String iconName) {
        JButton b = base(text, iconName);
        b.putClientProperty(FlatClientProperties.STYLE, DANGER_STYLE);
        return b;
    }

    /** 无边框工具栏按钮（hover 高亮）。 */
    public static JButton tool(String text, String iconName) {
        JButton b = base(text, iconName);
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        return b;
    }

    /** 节标题（加粗、略大），主题切换后自动恢复。 */
    public static JLabel section(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                Font f = getFont();
                setFont(f.deriveFont(Font.BOLD, Math.max(14.5f, f.getSize2D() + 1.5f)));
            }
        };
        Font f = l.getFont();
        l.setFont(f.deriveFont(Font.BOLD, Math.max(14.5f, f.getSize2D() + 1.5f)));
        return l;
    }

    /** 次要说明文字，颜色随主题更新。 */
    public static JLabel hint(String text) {
        JLabel l = new JLabel(text) {
            @Override
            public void updateUI() {
                super.updateUI();
                setForeground(dim());
            }
        };
        l.setForeground(dim());
        return l;
    }

    // ---------- 表格 ----------

    public static JTable table(TableModel m) {
        JTable t = new JTable(m);
        t.setRowHeight(26);
        t.setShowGrid(false);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(false);
        return t;
    }

    public static void width(JTable t, int viewColumn, int preferred) {
        if (t.getColumnModel().getColumnCount() > viewColumn) {
            t.getColumnModel().getColumn(viewColumn).setPreferredWidth(preferred);
        }
    }

    /** 将某列设为等宽字体（路径、命令、数值列对齐更美观）。 */
    public static void mono(JTable t, int viewColumn) {
        if (t.getColumnModel().getColumnCount() <= viewColumn) return;
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setFont(monoFont());
        t.getColumnModel().getColumn(viewColumn).setCellRenderer(r);
    }

    public static Font monoFont() {
        if (mono == null) {
            Font f = new Font("Consolas", Font.PLAIN, 13);
            if (!"Consolas".equalsIgnoreCase(f.getFamily())) {
                f = new Font(Font.MONOSPACED, Font.PLAIN, 13);
            }
            mono = f;
        }
        return mono;
    }

    // ---------- 文本 ----------

    public static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- 窗口几何记忆 ----------

    public static void rememberGeometry(JFrame frame) {
        Preferences p = prefs();
        Rectangle saved = readBounds(p);
        boolean onScreen = false;
        if (saved != null) {
            for (GraphicsDevice dev : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                for (GraphicsConfiguration gc : dev.getConfigurations()) {
                    if (gc.getBounds().intersects(saved)) {
                        onScreen = true;
                        break;
                    }
                }
            }
        }
        boolean maximized = p.getBoolean("window.maximized", false);
        if (saved != null && onScreen && !maximized) {
            frame.setBounds(saved);
        } else {
            frame.setSize(1280, 800);
            frame.setLocationRelativeTo(null);
        }
        if (maximized) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                try {
                    boolean isMax = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
                    Rectangle b = frame.getBounds();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream(32);
                    DataOutputStream out = new DataOutputStream(buf);
                    out.writeInt(b.x);
                    out.writeInt(b.y);
                    out.writeInt(b.width);
                    out.writeInt(b.height);
                    p.putByteArray("window.bounds", buf.toByteArray());
                    p.putBoolean("window.maximized", isMax);
                    p.flush();
                } catch (IOException | java.util.prefs.BackingStoreException ignored) {
                }
            }
        });
    }

    private static Rectangle readBounds(Preferences p) {
        byte[] data = p.getByteArray("window.bounds", null);
        if (data == null || data.length != 16) return null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return new Rectangle(in.readInt(), in.readInt(), in.readInt(), in.readInt());
        } catch (IOException e) {
            return null;
        }
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(Ui.class);
    }
}
