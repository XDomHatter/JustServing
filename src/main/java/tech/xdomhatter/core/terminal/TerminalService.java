package tech.xdomhatter.core.terminal;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import tech.xdomhatter.core.ssh.SshManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 交互式终端会话管理：在已有 SSH 连接上打开 shell 通道，跟踪生命周期并在退出时统一关闭。 */
public class TerminalService {

    private final SshManager ssh;
    private final Map<String, List<ShellHandle>> channels = new ConcurrentHashMap<>();

    public TerminalService(SshManager ssh) {
        this.ssh = ssh;
    }

    /** 在指定服务器上打开交互式 shell；cols/rows 为初始 PTY 尺寸。 */
    public ShellHandle open(String profileId, int cols, int rows) throws JSchException, java.io.IOException {
        Session s = ssh.session(profileId);
        ShellHandle ch = ShellChannel.open(s, cols, rows);
        channels.computeIfAbsent(profileId, k -> new CopyOnWriteArrayList<>()).add(ch);
        return ch;
    }

    /** 以 PTY 前台执行一条命令（应用前台调试用），并纳入生命周期跟踪。 */
    public ShellHandle openExec(String profileId, String command, int cols, int rows)
            throws JSchException, java.io.IOException {
        Session s = ssh.session(profileId);
        ShellHandle ch = ExecShellChannel.open(s, command, cols, rows);
        channels.computeIfAbsent(profileId, k -> new CopyOnWriteArrayList<>()).add(ch);
        return ch;
    }

    public List<ShellHandle> channelsOf(String profileId) {
        return List.copyOf(channels.getOrDefault(profileId, List.of()));
    }

    /** 关闭某服务器的全部终端通道（断开连接时调用）。 */
    public void closeProfile(String profileId) {
        List<ShellHandle> list = channels.remove(profileId);
        if (list != null) list.forEach(ShellHandle::close);
    }

    public void closeAll() {
        for (String id : List.copyOf(channels.keySet())) closeProfile(id);
    }
}
