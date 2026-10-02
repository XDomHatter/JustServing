package tech.xdomhatter.core.remote;

import org.junit.jupiter.api.Test;
import tech.xdomhatter.core.model.SshProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandServiceTest {

    @Test
    void nativeCommandBuildsSshArgs() {
        SshProfile p = new SshProfile();
        p.host = "example.com";
        p.port = 2222;
        p.user = "root";
        List<String> cmd = CommandService.nativeCommand(p, "");
        assertEquals("ssh", cmd.get(0));
        assertTrue(cmd.contains("root@example.com"));
        assertEquals("2222", cmd.get(cmd.indexOf("-p") + 1));
        assertTrue(cmd.contains("BatchMode=yes"), "无 TTY 场景必须禁用交互密码提示");
        assertTrue(cmd.contains("StrictHostKeyChecking=accept-new"));
        assertFalse(cmd.contains("-i"), "未配置私钥时不应传 -i");
        assertEquals(List.of(), cmd.subList(cmd.indexOf("root@example.com") + 1, cmd.size()),
                "远端命令由调用方追加");
    }

    @Test
    void nativeCommandIncludesKeyPath() {
        SshProfile p = new SshProfile();
        p.host = "h";
        p.port = 22;
        p.user = "root";
        p.keyPath = "/home/me/key";
        List<String> cmd = CommandService.nativeCommand(p, "C:\\Windows\\System32\\OpenSSH\\ssh.exe");
        assertEquals("C:\\Windows\\System32\\OpenSSH\\ssh.exe", cmd.get(0));
        assertTrue(cmd.indexOf("-i") >= 0);
        assertEquals("/home/me/key", cmd.get(cmd.indexOf("-i") + 1));
    }

    @Test
    void handleCollectsOutputAndRingBuffer() {
        ExecHandle h = new ExecHandle("p1", "测试机", "echo hi", false);
        for (int i = 0; i < ExecHandle.MAX_LINES + 10; i++) {
            h.append("line" + i, false);
        }
        List<String> lines = h.snapshotLines();
        assertEquals(ExecHandle.MAX_LINES + 1, lines.size(), "截断提示 + 最近 " + ExecHandle.MAX_LINES + " 行");
        assertTrue(lines.get(0).startsWith("…"), "超出部分应提示截断");
        assertEquals("line10", lines.get(1), "应保留最新的行");
        assertEquals("line" + (ExecHandle.MAX_LINES + 9), lines.get(lines.size() - 1));
    }

    @Test
    void stderrLinesArePrefixed() {
        ExecHandle h = new ExecHandle("p1", "m", "ls /nope", false);
        h.append("permission denied", true);
        assertEquals("[err] permission denied", h.snapshotLines().get(0));
    }

    @Test
    void finishSetsStateAndDuration() throws Exception {
        ExecHandle h = new ExecHandle("p1", "m", "uptime", false);
        assertEquals(ExecHandle.State.RUNNING, h.state);
        h.finish(ExecHandle.State.DONE, 0, null);
        assertEquals(ExecHandle.State.DONE, h.state);
        assertEquals(0, h.exitCode);
        assertTrue(h.isDone());
        assertTrue(h.success());
        assertTrue(h.durationMs() >= 0);
        assertEquals("完成", h.stateText());

        ExecHandle f = new ExecHandle("p1", "m", "bad", false);
        f.finish(ExecHandle.State.FAILED, -1, "boom");
        assertFalse(f.success());
        assertEquals("失败", f.stateText());
        assertEquals("boom", f.error);
    }
}
