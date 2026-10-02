package tech.xdomhatter.core.remote;

import org.junit.jupiter.api.Test;
import tech.xdomhatter.core.model.ServiceInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServiceManagerParseTest {

    private static final String SAMPLE = """
            @@UNITS
            nginx.service                loaded    active     running     A high performance web server and reverse proxy
            ssh.service                  loaded    active     running     OpenBSD Secure Shell server
            broken.service               loaded    failed     failed      Some broken unit
            cron.service                 loaded    inactive   dead        Regular background program processing
            @@FILES
            nginx.service                enabled   enabled
            ssh.service                  enabled   enabled
            broken.service               enabled   enabled
            cron.service                 disabled  disabled
            orphan.service               static    static
            @@END
            """;

    @Test
    void mergesUnitsAndUnitFiles() {
        List<ServiceInfo> list = ServiceManager.parse(SAMPLE);
        assertEquals(5, list.size());

        ServiceInfo nginx = byUnit(list, "nginx.service");
        assertEquals("active", nginx.active);
        assertEquals("running", nginx.sub);
        assertEquals("enabled", nginx.enabled);
        assertEquals("A high performance web server and reverse proxy", nginx.description);
        assertTrue(nginx.running());

        ServiceInfo cron = byUnit(list, "cron.service");
        assertEquals("inactive", cron.active);
        assertEquals("disabled", cron.enabled);
        assertFalse(cron.running());

        // 只出现在 unit-files 里的单元也要出现，并补默认状态
        ServiceInfo orphan = byUnit(list, "orphan.service");
        assertEquals("inactive", orphan.active);
        assertEquals("static", orphan.enabled);
    }

    @Test
    void runningUnitsSortFirst() {
        List<ServiceInfo> list = ServiceManager.parse(SAMPLE);
        // 运行中在前（nginx、ssh），failed 次之，其余按名称
        assertEquals("nginx.service", list.get(0).unit);
        assertEquals("ssh.service", list.get(1).unit);
        assertEquals("broken.service", list.get(2).unit);
    }

    @Test
    void nonServiceLinesIgnored() {
        String raw = """
                @@UNITS
                systemctl: using systemd
                @@FILES
                @@END
                """;
        // systemctl 提示行（非点分单元名）被忽略，结果为空供上层给出友好错误
        assertTrue(ServiceManager.parse(raw).isEmpty());
    }

    private static ServiceInfo byUnit(List<ServiceInfo> list, String unit) {
        return list.stream().filter(s -> s.unit.equals(unit)).findFirst().orElseThrow();
    }
}
