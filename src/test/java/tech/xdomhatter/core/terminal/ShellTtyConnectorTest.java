package tech.xdomhatter.core.terminal;

import com.jediterm.core.util.TermSize;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ShellTtyConnectorTest {

    /** 内存假通道。 */
    private static final class FakeShell implements ShellHandle {
        final ByteArrayInputStream in;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream errOut = new ByteArrayOutputStream();
        int cols = -1;
        int rows = -1;
        boolean closed;

        FakeShell(String content) {
            this.in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override public InputStream in() { return in; }

        @Override public OutputStream out() { return out; }

        @Override public InputStream err() { return new ByteArrayInputStream(new byte[0]); }

        @Override public void resize(int cols, int rows) {
            this.cols = cols;
            this.rows = rows;
        }

        @Override public boolean isClosed() { return closed; }

        @Override public void close() { closed = true; }
    }

    @Test
    void readDecodesUtf8() throws IOException {
        FakeShell sh = new FakeShell("中文ok");
        ShellTtyConnector c = new ShellTtyConnector(sh);
        char[] buf = new char[16];
        int n = c.read(buf, 0, buf.length);
        assertEquals(4, n);
        assertEquals("中文ok", new String(buf, 0, n));
        // EOF 后续读返回 -1
        assertEquals(-1, c.read(buf, 0, buf.length));
    }

    @Test
    void writeEncodesUtf8() throws IOException {
        FakeShell sh = new FakeShell("");
        ShellTtyConnector c = new ShellTtyConnector(sh);
        c.write("中key\r\n");
        assertArrayEquals("中key\r\n".getBytes(StandardCharsets.UTF_8), sh.out.toByteArray());
        c.write(new byte[]{0x1b, 0x5b, 0x41}); // ESC [ A
        byte[] all = sh.out.toByteArray();
        assertEquals(11, all.length);
        assertEquals(0x1b, all[8]);
    }

    @Test
    void resizeMapsToHandle() {
        FakeShell sh = new FakeShell("");
        ShellTtyConnector c = new ShellTtyConnector(sh);
        c.resize(new TermSize(120, 32));
        assertEquals(120, sh.cols);
        assertEquals(32, sh.rows);
    }

    @Test
    void connectedTracksHandleState() {
        FakeShell sh = new FakeShell("");
        ShellTtyConnector c = new ShellTtyConnector(sh);
        assertTrue(c.isConnected());
        sh.closed = true;
        assertFalse(c.isConnected());
    }

    @Test
    void closeClosesHandle() throws Exception {
        FakeShell sh = new FakeShell("x");
        ShellTtyConnector c = new ShellTtyConnector(sh);
        c.close();
        assertTrue(sh.closed);
        assertFalse(c.isConnected());
        // 关闭后写入被忽略（不抛异常）
        assertDoesNotThrow(() -> c.write("ignored"));
    }
}
