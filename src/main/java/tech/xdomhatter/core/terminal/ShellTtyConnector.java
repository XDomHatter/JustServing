package tech.xdomhatter.core.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** JediTerm 的 TtyConnector 适配：把 {@link ShellHandle} 的字节流桥接为终端模拟器的字符流（UTF-8）。 */
public class ShellTtyConnector implements TtyConnector {

    private final ShellHandle ch;
    private final Reader reader;
    private final OutputStream out;
    private volatile boolean closed;

    public ShellTtyConnector(ShellHandle ch) {
        this.ch = ch;
        this.reader = new InputStreamReader(ch.in(), StandardCharsets.UTF_8);
        this.out = ch.out();
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        return reader.read(buf, off, len);
    }

    @Override
    public void write(byte[] b) throws IOException {
        if (closed) return;
        synchronized (out) {
            out.write(b);
            out.flush();
        }
    }

    @Override
    public void write(String s) throws IOException {
        write(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return !closed && !ch.isClosed();
    }

    @Override
    public int waitFor() {
        try {
            while (isConnected()) Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return "ssh";
    }

    @Override
    public void resize(TermSize size) {
        if (!closed) ch.resize(size.getColumns(), size.getRows());
    }

    @Override
    public void close() {
        closed = true;
        ch.close();
    }
}
