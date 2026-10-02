package tech.xdomhatter.core.terminal;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** JSch shell 通道：带 PTY 的交互式会话，与 exec/sftp 通道共存于同一 SSH 连接。 */
public final class ShellChannel implements ShellHandle {

    private final ChannelShell channel;
    private final InputStream in;
    private final OutputStream out;
    private final InputStream err;

    private ShellChannel(ChannelShell channel) throws IOException {
        this.channel = channel;
        this.in = channel.getInputStream();
        this.out = channel.getOutputStream();
        this.err = channel.getExtInputStream();
    }

    /** 在已连接的 SSH 会话上打开交互式 shell；cols/rows 为初始 PTY 尺寸。 */
    public static ShellChannel open(Session session, int cols, int rows) throws JSchException, IOException {
        ChannelShell ch = (ChannelShell) session.openChannel("shell");
        ch.setPty(true);
        ch.setPtyType("xterm-256color");
        ch.setPtySize(Math.max(2, cols), Math.max(2, rows), 0, 0);
        ch.connect(10_000);
        return new ShellChannel(ch);
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
