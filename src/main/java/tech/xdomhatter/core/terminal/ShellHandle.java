package tech.xdomhatter.core.terminal;

import java.io.InputStream;
import java.io.OutputStream;

/** 交互式终端通道的抽象（实现：JSch ShellChannel；测试：内存假实现）。 */
public interface ShellHandle {

    /** 远端 → 本机 输出流（阻塞读）。 */
    InputStream in();

    /** 本机 → 远端 输入流。 */
    OutputStream out();

    /** 远端 stderr（PTY 模式下通常并入 stdout，防御性保留）。 */
    InputStream err();

    /** 通知远端 PTY 窗口尺寸变化。 */
    void resize(int cols, int rows);

    /** 通道是否已关闭/远端已退出。 */
    boolean isClosed();

    /** 关闭通道（远端 shell 会收到挂断）。 */
    void close();
}
