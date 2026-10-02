package tech.xdomhatter.core.tunnel;

import org.junit.jupiter.api.Test;
import tech.xdomhatter.core.model.AppConfig;
import tech.xdomhatter.core.model.FrpProxy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrpManagerTest {

    private static FrpProxy proxy(String name, String type, int local, int remote) {
        FrpProxy p = new FrpProxy();
        p.id = name;
        p.profileId = "srv";
        p.name = name;
        p.type = type;
        p.localIp = "127.0.0.1";
        p.localPort = local;
        p.remotePort = remote;
        return p;
    }

    @Test
    void frpcTomlContainsAll() {
        String toml = FrpManager.frpcToml("1.2.3.4", 7000, "s3cr3t",
                List.of(proxy("web", "tcp", 80, 8080), proxy("dns", "udp", 53, 6053)), 7400);
        assertTrue(toml.contains("serverAddr = \"1.2.3.4\""));
        assertTrue(toml.contains("serverPort = 7000"));
        assertTrue(toml.contains("auth.token = \"s3cr3t\""));
        assertTrue(toml.contains("webServer.port = 7400"));
        assertTrue(toml.contains("[[proxies]]"));
        assertTrue(toml.contains("name = \"web\""));
        assertTrue(toml.contains("type = \"udp\""));
        assertTrue(toml.contains("remotePort = 6053"));
        // 顺序
        assertTrue(toml.indexOf("name = \"web\"") < toml.indexOf("name = \"dns\""));
    }

    @Test
    void frpcTomlSkipsDisabledAndOptionalAdmin() {
        FrpProxy off = proxy("off", "tcp", 80, 8080);
        off.enabled = false;
        String toml = FrpManager.frpcToml("h", 7000, null, List.of(off), 0);
        assertFalse(toml.contains("[[proxies]]"));
        assertFalse(toml.contains("auth.token"));
        assertFalse(toml.contains("webServer"));
    }

    @Test
    void frpcTomlEscapesQuotes() {
        String toml = FrpManager.frpcToml("a\"b", 7000, "t\"x", List.of(), 0);
        assertTrue(toml.contains("serverAddr = \"a\\\"b\""));
        assertTrue(toml.contains("auth.token = \"t\\\"x\""));
    }

    @Test
    void frpsToml() {
        String toml = FrpManager.frpsToml(7000, "tok");
        assertTrue(toml.contains("bindPort = 7000"));
        assertTrue(toml.contains("auth.token = \"tok\""));
        String noToken = FrpManager.frpsToml(7000, "");
        assertFalse(noToken.contains("auth.token"));
    }

    @Test
    void unitFile() {
        AppConfig.Settings st = new AppConfig.Settings();
        st.frpsRemotePath = "/usr/local/bin/frps";
        st.frpsConfigPath = "/etc/frp/frps.toml";
        String unit = FrpManager.unitFile(st);
        assertTrue(unit.contains("ExecStart=/usr/local/bin/frps -c /etc/frp/frps.toml"));
        assertTrue(unit.contains("WantedBy=multi-user.target"));
    }

    @Test
    void shellQuote() {
        assertEquals("'a b'", FrpManager.q("a b"));
        assertEquals("'a'\\''b'", FrpManager.q("a'b"));
    }

    @Test
    void unitFileFromPaths() {
        String unit = FrpManager.unitFile("/opt/frp/frps", "/etc/frp/my.toml");
        assertTrue(unit.contains("ExecStart=/opt/frp/frps -c /etc/frp/my.toml"));
        assertTrue(unit.contains("WantedBy=multi-user.target"));
        String noConf = FrpManager.unitFile("/usr/bin/frps", null);
        assertTrue(noConf.contains("ExecStart=/usr/bin/frps\n"));
        assertFalse(noConf.contains(" -c "));
    }

    @Test
    void pickUnitPrefersExactFrps() {
        assertEquals("frps", FrpManager.pickUnit("frpc.service\nfrps.service\nfrps-hk.service\n"));
        assertEquals("frps-hk", FrpManager.pickUnit("frpc.service\nfrps-hk.service\n"));
        assertEquals("MyFrps", FrpManager.pickUnit("MyFrps.service\n"));
        assertNull(FrpManager.pickUnit("frpc.service\nsshd.service\n"));
        assertNull(FrpManager.pickUnit(""));
        assertNull(FrpManager.pickUnit(null));
    }

    @Test
    void configFromArgs() {
        assertEquals("/etc/frp/frps.toml", FrpManager.configFromArgs(List.of("./frps", "-c", "/etc/frp/frps.toml")));
        assertEquals("/etc/frp/frps.toml", FrpManager.configFromArgs(List.of("frps", "--config", "/etc/frp/frps.toml")));
        assertEquals("/etc/frp/frps.toml", FrpManager.configFromArgs(List.of("frps", "--config=/etc/frp/frps.toml")));
        assertNull(FrpManager.configFromArgs(List.of("frps", "-p", "7000")));
        assertNull(FrpManager.configFromArgs(null));
    }

    @Test
    void pickFirstNonEmpty() {
        assertEquals("/usr/bin/frps", FrpManager.pickFirst("\n  \n/usr/bin/frps\n/opt/frp/frps\n"));
        assertEquals("", FrpManager.pickFirst(" \n "));
        assertEquals("", FrpManager.pickFirst(null));
    }

    @Test
    void parseBindPortTomlAndIni() {
        assertEquals(7000, FrpManager.parseBindPort("bindPort = 7000\nauth.token = \"x\"\n"));
        assertEquals(7500, FrpManager.parseBindPort("[common]\nbind_port = 7500 # main\n"));
        assertEquals(7600, FrpManager.parseBindPort("bindPort = \"7600\"\n"));
        assertNull(FrpManager.parseBindPort("serverPort = 7000\nvhostHTTPPort = 80\n"));
        assertNull(FrpManager.parseBindPort(null));
    }

    @Test
    void parseProcProbe() {
        FrpManager.ProcProbe p = FrpManager.parseProcProbe(
                "42\nEXE=/usr/local/bin/frps\nARGS_BEGIN\n/usr/local/bin/frps\n-c\n/etc/frp/frps.toml\nARGS_END\n");
        assertEquals(42, p.pid());
        assertEquals("/usr/local/bin/frps", p.exe());
        assertEquals(List.of("/usr/local/bin/frps", "-c", "/etc/frp/frps.toml"), p.args());

        FrpManager.ProcProbe none = FrpManager.parseProcProbe("\n \n");
        assertNull(none.pid());
        assertNull(none.exe());
        assertEquals(List.of(), none.args());
    }

    @Test
    void parseTomlFieldsDottedAndSection() {
        String text = "bindPort = 7000 # main\nauth.token = \"abc\"\n\n[webServer]\naddr = \"127.0.0.1\"\n"
                + "port = 7400\nuser = admin\n# comment\ntoken = \"x\"\n";
        var m = FrpManager.parseTomlFields(text);
        assertEquals("7000", m.get("bindPort"));
        assertEquals("\"abc\"", m.get("auth.token"));
        assertEquals("\"127.0.0.1\"", m.get("webServer.addr"));
        assertEquals("7400", m.get("webServer.port"));
        assertEquals("admin", m.get("webServer.user"));
        assertEquals("\"x\"", m.get("webServer.token"));
        assertNull(m.get("token"));
    }

    @Test
    void applyFrpsEditsReplacesInPlaceKeepsComments() {
        String orig = "bindPort = 7000 # main\nauth.token = \"old\"\n\n[webServer]\nport = 7400\n";
        String out = FrpManager.applyFrpsEdits(orig,
                new FrpManager.FrpsEdits("7500", "new", null, null, null, null, "7501", null, null));
        assertTrue(out.contains("bindPort = 7500 # main"));
        assertTrue(out.contains("auth.token = \"new\""));
        assertTrue(out.contains("port = 7501"));
        assertTrue(out.contains("[webServer]"));
        assertFalse(out.contains("7000"));
        assertFalse(out.contains("old"));
    }

    @Test
    void applyFrpsEditsNoChangeReturnsOriginal() {
        String orig = "bindPort = 7000\n\n[webServer]\nport = 1\n";
        String out = FrpManager.applyFrpsEdits(orig,
                new FrpManager.FrpsEdits(null, null, null, null, null, null, null, null, null));
        assertEquals(orig, out);
    }

    @Test
    void applyFrpsEditsAddsTopLevelBeforeFirstSection() {
        String orig = "bindPort = 7000\n\n[webServer]\nport = 1\n";
        String out = FrpManager.applyFrpsEdits(orig,
                new FrpManager.FrpsEdits("8000", "t0k", null, null, "demo.example.com", null, null, null, null));
        assertTrue(out.indexOf("bindPort = 8000") < out.indexOf("[webServer]"));
        assertTrue(out.indexOf("subdomainHost = \"demo.example.com\"") < out.indexOf("[webServer]"));
        int authHeader = out.indexOf("[auth]");
        assertTrue(authHeader > 0);
        assertTrue(out.indexOf("token = \"t0k\"", authHeader) > authHeader);
    }

    @Test
    void applyFrpsEditsMergesIntoExistingSection() {
        String orig = "bindPort = 7000\n\n[webServer]\nport = 7400\n";
        String out = FrpManager.applyFrpsEdits(orig,
                new FrpManager.FrpsEdits(null, null, null, null, null, "127.0.0.1", null, "admin", "pw1"));
        int header = out.indexOf("[webServer]");
        assertTrue(out.indexOf("addr = \"127.0.0.1\"", header) > header);
        assertTrue(out.indexOf("user = \"admin\"") > out.indexOf("port = 7400"));
        assertTrue(out.contains("password = \"pw1\""));
    }

    @Test
    void applyFrpsEditsAppendsNewSectionAndRemovesKey() {
        String orig = "bindPort = 7000\nvhostHTTPPort = 80\n";
        String out = FrpManager.applyFrpsEdits(orig,
                new FrpManager.FrpsEdits(null, null, "", null, null, null, "7500", null, null));
        assertFalse(out.contains("vhostHTTPPort"));
        assertTrue(out.contains("bindPort = 7000"));
        assertTrue(out.contains("[webServer]"));
        assertTrue(out.contains("port = 7500"));
        assertTrue(out.indexOf("bindPort = 7000") < out.indexOf("[webServer]"));
    }
}
