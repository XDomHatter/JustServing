package tech.xdomhatter.core.terminal;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 带PTY的exec通道：在终端界面中前台运行一条命令（如应用的启动命令）。
 * stdin=键盘输入、stdout/stderr=终端屏幕；通道关闭或窗口关闭时远端进程收到挂断。
 */
public final class ExecShellChannel implements ShellHandle {

    private final ChannelExec channel;
    private final InputStream in;
    private final OutputStream out;
    private final InputStream err;

    private ExecShellChannel(ChannelExec channel) throws IOException {
        this.channel = channel;
        this.in = channel.getInputStream();
        this.out = channel.getOutputStream();
        this.err = channel.getExtInputStream();
    }

    /** 在已连接的 SSH 会话上以 PTY 前台执行 command；cols/rows 为初始终端尺寸。 */
    public static ExecShellChannel open(Session session, String command, int cols, int rows)
            throws JSchException, IOException {
        ChannelExec ch = (ChannelExec) session.openChannel("exec");
        ch.setCommand(command);
        ch.setPty(true);
        ch.setPtyType("xterm-256color");
        ch.setPtySize(Math.max(2, cols), Math.max(2, rows), 0, 0);
        ch.connect(10_000);
        return new ExecShellChannel(ch);
    }

    @Override
    public InputStream in() {
        return in;
    }

    @Override
    public OutputStream out() {
        return out;
    }

    @Override
    public InputStream err() {
        return err;
    }

    @Override
    public void resize(int cols, int rows) {
        try {
            channel.setPtySize(Math.max(2, cols), Math.max(2, rows), 0, 0);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isClosed() {
        return channel.isClosed() || channel.isEOF();
    }

    @Override
    public void close() {
        channel.disconnect();
    }
}
