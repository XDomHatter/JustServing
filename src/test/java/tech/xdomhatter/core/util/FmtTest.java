package tech.xdomhatter.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FmtTest {

    @Test
    void bytes() {
        assertEquals("0 B", Fmt.bytes(0));
        assertEquals("512 B", Fmt.bytes(512));
        assertEquals("1.0 KiB", Fmt.bytes(1024));
        assertEquals("1.5 KiB", Fmt.bytes(1536));
        assertEquals("1.0 MiB", Fmt.bytes(1024 * 1024));
        assertEquals("-", Fmt.bytes(-1));
    }

    @Test
    void speed() {
        assertEquals("2.0 KiB/s", Fmt.speed(2048));
    }

    @Test
    void pctAndBar() {
        assertEquals("42.3%", Fmt.pct(42.3));
        String bar = Fmt.bar("CPU ", 50, 10);
        assertTrue(bar.startsWith("CPU  [█████░░░░░] 50.0%"), "实际: " + bar);
        assertEquals("CPU  [██████████] 100.0%", Fmt.bar("CPU ", 120, 10));
        assertEquals("CPU  [░░░░░░░░░░] 0.0%", Fmt.bar("CPU ", -5, 10));
    }

    @Test
    void duration() {
        assertEquals("59秒", Fmt.duration(59));
        assertEquals("1分0秒", Fmt.duration(60));
        assertEquals("1小时5分", Fmt.duration(3900));
    }
}
