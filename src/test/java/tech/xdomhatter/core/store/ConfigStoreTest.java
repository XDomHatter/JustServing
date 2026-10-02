package tech.xdomhatter.core.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.xdomhatter.core.model.CommandTask;
import tech.xdomhatter.core.model.FrpProxy;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.model.TunnelSpec;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir
    Path dir;

    @Test
    void saveLoadRoundtrip() {
        Path file = dir.resolve("config.json");
        ConfigStore store = new ConfigStore(file);
        SshProfile p = new SshProfile();
        p.id = "srv1";
        p.name = "测试";
        p.host = "1.2.3.4";
        p.port = 2222;
        p.user = "root";
        p.authType = SshProfile.AuthType.KEY;
        p.keyPath = "/home/me/key";
        store.get().profiles.add(p);

        TunnelSpec t = new TunnelSpec();
        t.id = "t1";
        t.profileId = "srv1";
        t.type = TunnelSpec.Type.REMOTE;
        t.listenPort = 8080;
        store.get().tunnels.add(t);

        FrpProxy x = new FrpProxy();
        x.id = "x1";
        x.profileId = "srv1";
        x.name = "web";
        store.get().frpProxies.add(x);
        store.save();

        ConfigStore store2 = new ConfigStore(file);
        assertEquals(1, store2.get().profiles.size());
        SshProfile loaded = store2.get().profiles.get(0);
        assertEquals("测试", loaded.name);
        assertEquals(2222, loaded.port);
        assertEquals(SshProfile.AuthType.KEY, loaded.authType);
        assertEquals(1, store2.tunnelsOf("srv1").size());
        assertEquals(1, store2.proxiesOf("srv1").size());
    }

    @Test
    void removeProfileCascades() {
        ConfigStore store = new ConfigStore(dir.resolve("c2.json"));
        SshProfile p = new SshProfile();
        p.id = "srv1";
        p.host = "h";
        store.get().profiles.add(p);

        TunnelSpec t = new TunnelSpec();
        t.id = "t1";
        t.profileId = "srv1";
        store.get().tunnels.add(t);

        FrpProxy x = new FrpProxy();
        x.id = "x1";
        x.profileId = "srv1";
        store.get().frpProxies.add(x);

        CommandTask c = new CommandTask();
        c.id = "c1";
        c.profileId = "srv1";
        store.get().commandTasks.add(c);

        store.removeProfile("srv1");
        assertTrue(store.get().profiles.isEmpty());
        assertTrue(store.get().tunnels.isEmpty());
        assertTrue(store.get().frpProxies.isEmpty());
        assertTrue(store.get().commandTasks.isEmpty());
    }

    @Test
    void commandTaskRoundtripAndScope() {
        Path file = dir.resolve("c3.json");
        ConfigStore store = new ConfigStore(file);
        SshProfile p = new SshProfile();
        p.id = "srv1";
        p.name = "测试";
        store.get().profiles.add(p);

        CommandTask bound = new CommandTask();
        bound.id = "c1";
        bound.name = "重启nginx";
        bound.command = "systemctl restart nginx";
        bound.profileId = "srv1";
        bound.timeoutSec = 60;
        bound.transport = CommandTask.Transport.NATIVE;
        CommandTask global = new CommandTask();
        global.id = "c2";
        global.name = "查看负载";
        global.command = "uptime";
        global.profileId = "";
        store.get().commandTasks.add(bound);
        store.get().commandTasks.add(global);
        store.save();

        ConfigStore loaded = new ConfigStore(file);
        assertEquals(2, loaded.get().commandTasks.size());
        CommandTask t = loaded.get().commandTasks.get(0);
        assertEquals("重启nginx", t.name);
        assertEquals(60, t.timeoutSec);
        assertEquals(CommandTask.Transport.NATIVE, t.transport);
        // tasksFor：绑定服务器的 + 全局的都可见
        assertEquals(2, loaded.tasksFor("srv1").size());
        assertEquals(1, loaded.tasksFor("other").size());
    }

    @Test
    void missingFileGivesDefaults() {
        ConfigStore store = new ConfigStore(dir.resolve("nope.json"));
        assertNotNull(store.get().settings);
        assertTrue(store.get().profiles.isEmpty());
        assertEquals(3000, store.get().settings.monitorIntervalMs);
    }
}
