package tech.xdomhatter;

import tech.xdomhatter.core.AppContext;
import tech.xdomhatter.ui.swing.SwingApp;

import java.awt.GraphicsEnvironment;

public class Main {
    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if ("--tui".equalsIgnoreCase(a)) {
                System.err.println("TUI 模块已移除，请使用 GUI 启动（默认或 --gui）。");
                System.exit(1);
            }
        }
        AppContext ctx = AppContext.load();
        // 清理必须挂在 shutdown hook 上：窗口关闭走 EXIT_ON_CLOSE 的 System.exit（finally 不会执行），
        // 且 SwingApp.run 阻塞前绝不能提前关闭传输池等服务；所有退出路径都经过 System.exit，此处只执行一次
        Runtime.getRuntime().addShutdownHook(new Thread(ctx::shutdown, "ctx-cleanup"));
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有可用的图形界面，无法启动（本工具仅提供 GUI）。");
            System.exit(1);
        }
        SwingApp.run(ctx);
    }
}
