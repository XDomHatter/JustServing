package tech.xdomhatter.core.apps;

import org.junit.jupiter.api.Test;
import tech.xdomhatter.core.model.ManagedApp;
import tech.xdomhatter.core.apps.RemoteAppStore.State;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAppStoreTest {

    @Test
    void serializeParseRoundTrip() {
        ManagedApp a = new ManagedApp();
        a.id = "a1b2c3d4";
        a.name = "订单服务";
        a.deployDir = "/opt/order-app";
        a.runMode = ManagedApp.RunMode.SYSTEMD;
        a.startCommand = "java -jar app.jar --port=8080";
        a.stopCommand = "";
        a.stdinPath = "/dev/null";
        a.stdoutPath = "/root/.justserving/logs/a1b2c3d4.out.log";
        a.stderrPath = "/root/.justserving/logs/a1b2c3d4.err.log";
        a.env = new ArrayList<>(List.of("JAVA_OPTS=-Xms64m", "TZ=Asia/Shanghai"));
        a.sourceType = ManagedApp.SourceType.GIT;
        a.gitUrl = "https://git.example.com/team/order.git";
        a.gitBranch = "main";
        a.gitAuth = "token";
        a.autoStart = true;
        a.extraPaths = new ArrayList<>(List.of("/var/data/order"));
        a.createdAt = "2026-09-29T10:00";
        a.updatedAt = "2026-09-29T11:00";

        State st = new State();
        st.apps.add(a);
        State back = RemoteAppStore.parse(RemoteAppStore.serialize(st));

        assertEquals(1, back.apps.size());
        ManagedApp b = back.apps.get(0);
        assertEquals(a.id, b.id);
        assertEquals(a.name, b.name);
        assertEquals(a.deployDir, b.deployDir);
        assertEquals(ManagedApp.RunMode.SYSTEMD, b.runMode);
        assertEquals(a.startCommand, b.startCommand);
        assertEquals(a.env, b.env);
        assertEquals(ManagedApp.SourceType.GIT, b.sourceType);
        assertEquals(a.gitUrl, b.gitUrl);
        assertEquals(a.gitBranch, b.gitBranch);
        assertEquals("token", b.gitAuth);
        assertTrue(b.autoStart);
        assertEquals(a.extraPaths, b.extraPaths);
        assertEquals(a.createdAt, b.createdAt);
    }

    @Test
    void missingFieldsGetDefaults() {
        State st = RemoteAppStore.parse("""
                {"version":1,"apps":[
                  {"id":"x1","name":"legacy"},
                  {"id":"x2","name":"partial","env":["BAD_NO_EQUALS","GOOD=1",null],
                   "extraPaths":["",null,"/var/data"],"stdinPath":"","runMode":null,"sourceType":null},
                  {"id":"","name":"no id dropped"},
                  null
                ]}
                """);
        assertEquals(2, st.apps.size());

        ManagedApp x1 = st.apps.get(0);
        assertEquals(ManagedApp.RunMode.DETACHED, x1.runMode);
        assertEquals(ManagedApp.SourceType.UPLOAD, x1.sourceType);
        assertEquals("/dev/null", x1.stdinPath);
        assertEquals("", x1.stdoutPath);
        assertTrue(x1.env.isEmpty());
        assertTrue(x1.extraPaths.isEmpty());

        ManagedApp x2 = st.apps.get(1);
        assertEquals(List.of("GOOD=1"), x2.env);
        assertEquals(List.of("/var/data"), x2.extraPaths);
    }

    @Test
    void blankOrNullJsonYieldsEmptyState() {
        assertEquals(0, RemoteAppStore.parse(null).apps.size());
        assertEquals(0, RemoteAppStore.parse("   ").apps.size());
        assertEquals(0, RemoteAppStore.parse("{}").apps.size());
    }
}
