package tech.xdomhatter.core;

import tech.xdomhatter.core.apps.AppManager;
import tech.xdomhatter.core.monitor.MonitorService;
import tech.xdomhatter.core.openwith.OpenWithService;
import tech.xdomhatter.core.remote.CommandService;
import tech.xdomhatter.core.remote.ServiceManager;
import tech.xdomhatter.core.sftp.TransferService;
import tech.xdomhatter.core.ssh.SshManager;
import tech.xdomhatter.core.store.AppPaths;
import tech.xdomhatter.core.store.ConfigStore;
import tech.xdomhatter.core.store.CredentialVault;
import tech.xdomhatter.core.terminal.TerminalService;
import tech.xdomhatter.core.tunnel.FrpManager;
import tech.xdomhatter.core.tunnel.SshTunnelManager;

/** 核心服务装配：所有业务逻辑都在 core，GUI/TUI 仅是两种表现层。 */
public class AppContext {
    public final ConfigStore config;
    public final CredentialVault vault;
    public final SshManager ssh;
    public final TransferService transfers;
    public final MonitorService monitor;
    public final SshTunnelManager tunnels;
    public final FrpManager frp;
    public final OpenWithService openWith;
    public final CommandService commands;
    public final ServiceManager services;
    public final TerminalService terminals;
    public final AppManager apps;

    private AppContext(ConfigStore config, CredentialVault vault, SshManager ssh,
                       TransferService transfers, MonitorService monitor,
                       SshTunnelManager tunnels, FrpManager frp, OpenWithService openWith,
                       CommandService commands, ServiceManager services, TerminalService terminals,
                       AppManager apps) {
        this.config = config;
        this.vault = vault;
        this.ssh = ssh;
        this.transfers = transfers;
        this.monitor = monitor;
        this.tunnels = tunnels;
        this.frp = frp;
        this.openWith = openWith;
        this.commands = commands;
        this.services = services;
        this.terminals = terminals;
        this.apps = apps;
    }

    public static AppContext load() {
        ConfigStore config = new ConfigStore(AppPaths.CONFIG);
        CredentialVault vault = new CredentialVault(AppPaths.VAULT);
        SshManager ssh = new SshManager(vault);
        TransferService transfers = new TransferService(ssh, config.get().settings.transferThreads);
        MonitorService monitor = new MonitorService(ssh);
        monitor.setInterval(config.get().settings.monitorIntervalMs);
        SshTunnelManager tunnels = new SshTunnelManager(ssh);
        FrpManager frp = new FrpManager(ssh, vault);
        OpenWithService openWith = new OpenWithService(config);
        CommandService commands = new CommandService(ssh, config);
        ServiceManager services = new ServiceManager(ssh);
        TerminalService terminals = new TerminalService(ssh);
        AppManager apps = new AppManager(ssh, vault, transfers);
        return new AppContext(config, vault, ssh, transfers, monitor, tunnels, frp, openWith, commands, services, terminals, apps);
    }

    public void shutdown() {
        monitor.stopAll();
        tunnels.stopAll();
        transfers.shutdown();
        frp.stopAllFrpc();
        terminals.closeAll();
        ssh.disconnectAll();
    }
}
