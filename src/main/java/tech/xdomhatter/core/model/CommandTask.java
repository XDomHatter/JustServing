package tech.xdomhatter.core.model;

/** 远程命令任务：可在指定服务器（或全局所有服务器）上重复执行的一条命令。 */
public class CommandTask {
    /** 执行通道：JSch（内建 SSH 库，用保险库凭据） / NATIVE（系统 ssh 客户端，走密钥/agent）。 */
    public enum Transport { JSCH, NATIVE }

    public static final int[] TIMEOUT_CHOICES = {10, 30, 60, 300, 1800};

    public String id;
    public String name = "";
    public String command = "";
    /** 绑定的服务器 profileId；空 = 全局任务（对当前选中的任意服务器执行）。 */
    public String profileId = "";
    public int timeoutSec = 30;
    public Transport transport = Transport.JSCH;

    public String transportText() {
        return transport == Transport.NATIVE ? "原生ssh" : "JSch";
    }

    public String display() {
        return name + "  (" + transportText() + ", 超时" + timeoutSec + "s)";
    }
}
