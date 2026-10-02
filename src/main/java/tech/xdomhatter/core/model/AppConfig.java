package tech.xdomhatter.core.model;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public List<SshProfile> profiles = new ArrayList<>();
    public List<OpenBinding> bindings = new ArrayList<>();
    public List<TunnelSpec> tunnels = new ArrayList<>();
    public List<FrpProxy> frpProxies = new ArrayList<>();
    public List<CommandTask> commandTasks = new ArrayList<>();
    public Settings settings = new Settings();

    public static class Settings {
        public int monitorIntervalMs = 3000;
        public int transferThreads = 3;
        /** 原生 ssh 可执行文件（远程命令的 NATIVE 通道用）。 */
        public String sshPath = "ssh";
        /** 单次执行命令的默认超时（秒）。 */
        public int commandTimeoutSec = 30;
        public String frpcPath = "";
        public String frpsRemotePath = "/usr/local/bin/frps";
        public String frpsUnitName = "frps";
        public String frpsConfigPath = "/etc/frp/frps.toml";
        public int frpsBindPort = 7000;
        public int frpcAdminPort = 7400;
        public boolean frpcAdminEnabled = true;
    }
}
