package tech.xdomhatter.core.apps;

import org.junit.jupiter.api.Test;
import tech.xdomhatter.core.model.ManagedApp;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AppManagerTest {

    private static ManagedApp app() {
        ManagedApp a = new ManagedApp();
        a.id = "a1b2c3d4";
        a.name = "order-app";
        a.deployDir = "/opt/order-app";
        a.startCommand = "java -jar app.jar";
        a.stdinPath = "/dev/null";
        a.stdoutPath = "/root/.justserving/logs/a1b2c3d4.out.log";
        a.stderrPath = "/root/.justserving/logs/a1b2c3d4.err.log";
        return a;
    }

    // ---------- shell 构造 ----------

    @Test
    void posixQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", AppManager.q("it's"));
        assertEquals("''", AppManager.q(""));
    }

    @Test
    void envPrefixBuildsPlainExports() {
        ManagedApp a = app();
        a.env = List.of("TZ=Asia/Shanghai");
        assertEquals("export 'TZ=Asia/Shanghai'; ", AppManager.envPrefix(a));
    }

    @Test
    void startCommandUsesSetsidPidFileAndRedirects() {
        ManagedApp a = app();
        a.env = List.of("TZ=Asia/Shanghai", "JAVA_OPTS='-Xms64m'");
        String cmd = AppManager.startShellCommand(a, "/root");

        assertTrue(cmd.contains("setsid bash -c "));
        // 环境变量以 export 形式嵌入脚本（引号由 q() 统一转义，见 envPrefix/q 专项测试）
        assertTrue(cmd.contains("TZ=Asia/Shanghai"));
        assertTrue(cmd.contains("exec java -jar app.jar"));
        assertTrue(cmd.contains("< '/dev/null'"));
        assertTrue(cmd.contains("> '/root/.justserving/logs/a1b2c3d4.out.log'"));
        assertTrue(cmd.contains("2> '/root/.justserving/logs/a1b2c3d4.err.log'"));
        assertTrue(cmd.contains("echo $! > '/root/.justserving/pids/a1b2c3d4.pid'"));
        assertTrue(cmd.contains("cd '/opt/order-app'"));
        assertTrue(cmd.contains("@@STARTED"));
        assertTrue(cmd.contains("@@ALREADY"));
    }

    @Test
    void stopCommandKillsProcessGroupThenForces() {
        String cmd = AppManager.stopShellCommand(app(), "/root");
        assertTrue(cmd.contains("kill -- -\"$pid\""));
        assertTrue(cmd.contains("kill -9 -- -\"$pid\""));
        assertTrue(cmd.contains("rm -f \"$p\""));
        assertFalse(cmd.contains("bash -c"));
    }

    @Test
    void customStopCommandTakesPrecedence() {
        ManagedApp a = app();
        a.stopCommand = "./bin/stop.sh --graceful";
        String cmd = AppManager.stopShellCommand(a, "/root");
        assertTrue(cmd.contains("bash -c './bin/stop.sh --graceful'"));
        assertFalse(cmd.contains("kill --"));
    }

    @Test
    void debugCommandRunsForegroundWithoutRedirects() {
        ManagedApp a = app();
        a.env = List.of("A=1");
        String cmd = AppManager.debugCommand(a);
        assertTrue(cmd.startsWith("cd '/opt/order-app' && { export 'A=1'; exec java -jar app.jar; }"));
        assertFalse(cmd.contains("setsid"));
        assertFalse(cmd.contains(" > "));
    }

    // ---------- 状态解析 ----------

    @Test
    void parseDetachedStatus() {
        AppManager.AppStatus st = AppManager.parseStatusSection("RUNNING\nPID=1234\n  02:34  \n");
        assertTrue(st.running());
        assertEquals("1234", st.pid());
        assertEquals("02:34", st.uptime());
        assertEquals("● 运行中 02:34", st.text());

        AppManager.AppStatus stopped = AppManager.parseStatusSection("STOPPED\n");
        assertFalse(stopped.running());
        assertEquals("○ 已停止", stopped.text());
    }

    @Test
    void parseSystemdStatusNormalizesStates() {
        assertEquals("RUNNING", AppManager.parseStatusSection("active\n1h02m\n").state());
        assertEquals("FAILED", AppManager.parseStatusSection("failed\n").state());
        assertEquals("STARTING", AppManager.parseStatusSection("activating\n").state());
        assertEquals("STOPPED", AppManager.parseStatusSection("inactive\n").state());
        assertEquals("STOPPED", AppManager.parseStatusSection("").state());
    }

    @Test
    void parseBatchStatusByAppMarkers() {
        ManagedApp a = app();
        ManagedApp b = app();
        b.id = "b2";
        String raw = "@@APP:a1b2c3d4\nRUNNING\nPID=11\n5days\n@@APP:b2\nSTOPPED\n@@END\n";
        var map = AppManager.parseStatusBatch(raw);
        assertEquals(2, map.size());
        assertTrue(map.get("a1b2c3d4").running());
        assertEquals("5days", map.get("a1b2c3d4").uptime());
        assertFalse(map.get("b2").running());

        String batch = AppManager.statusBatchCommand(List.of(a, b), "/root");
        assertTrue(batch.contains("@@APP:a1b2c3d4"));
        assertTrue(batch.contains("@@APP:b2"));
        assertTrue(batch.contains("/root/.justserving/pids/a1b2c3d4.pid"));
        assertTrue(batch.contains("@@END"));
    }

    // ---------- git ----------

    @Test
    void injectTokenIntoHttpsUrls() {
        assertEquals("https://tk@git.example.com/team/a.git",
                AppManager.injectToken("https://git.example.com/team/a.git", "tk"));
        assertEquals("https://user:tk@git.example.com/team/a.git",
                AppManager.injectToken("https://user@git.example.com/team/a.git", "tk"));
        assertEquals("git@git.example.com:team/a.git",
                AppManager.injectToken("git@git.example.com:team/a.git", "tk"));
        assertEquals("https://git.example.com/x.git",
                AppManager.injectToken("https://git.example.com/x.git", "  "));
    }

    // ---------- systemd 单元 ----------

    @Test
    void unitFileEscapesDollarAndQuote() {
        ManagedApp a = app();
        a.runMode = ManagedApp.RunMode.SYSTEMD;
        a.env = List.of("MSG=hello \"world\"");
        a.startCommand = "echo \"$HOME/boot $x\"";
        String unit = AppManager.unitFile(a, "root");

        assertTrue(unit.contains("WorkingDirectory=/opt/order-app"));
        assertTrue(unit.contains("Environment=\"MSG=hello \\\"world\\\"\""));
        assertTrue(unit.contains("ExecStart=/bin/bash -c \"exec echo \\\"$$HOME/boot $$x\\\"\""));
        assertTrue(unit.contains("StandardInput=file:/dev/null"));
        assertTrue(unit.contains("StandardOutput=append:/root/.justserving/logs/a1b2c3d4.out.log"));
        assertTrue(unit.contains("Restart=on-failure"));
        assertFalse(unit.contains("User="), "root 用户不应写 User=");
        assertTrue(AppManager.unitFile(a, "deploy").contains("User=deploy"));
    }

    @Test
    void unitNameIsSanitized() {
        ManagedApp a = app();
        a.name = "我的 App v2!";
        assertEquals("justserving----app-v2-", AppManager.unitName(a));
        ManagedApp blank = app();
        blank.name = "  ";
        assertEquals("justserving-app", AppManager.unitName(blank));
        // 纯 CJK 名称无 ASCII 字符，退化为 app-<id> 避免重名
        ManagedApp cjk = app();
        cjk.name = "订单服务";
        assertEquals("justserving-app-a1b2c3d4", AppManager.unitName(cjk));
    }

    // ---------- 路径与下载 ----------

    @Test
    void expandHomeVariants() {
        assertEquals("/root", AppManager.expandHome("~", "/root"));
        assertEquals("/root/apps/x", AppManager.expandHome("~/apps/x", "/root"));
        assertEquals("/opt/x", AppManager.expandHome("/opt/x", "/root"));
        assertEquals("", AppManager.expandHome(null, "/root"));
    }

    @Test
    void extraTargetNamesDedupe() {
        Set<String> used = new java.util.HashSet<>();
        assertEquals("db", AppManager.extraTargetName("/var/data/db", used));
        used.add("db");
        assertEquals("db-2", AppManager.extraTargetName("/other/db", used));
        assertEquals("logs", AppManager.extraTargetName("/var/log/logs/", used));
    }

    @Test
    void dirnameHandlesRootAndDeepPaths() {
        assertEquals("/root/.justserving/pids", AppManager.dirname("/root/.justserving/pids/x.pid"));
        assertEquals("/", AppManager.dirname("/root"));
        assertEquals("/", AppManager.dirname("/x.pid"));
    }
}
