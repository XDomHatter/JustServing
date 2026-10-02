package tech.xdomhatter.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 企业服务管理的应用定义：部署目录、启停方式、输入输出与额外数据路径。
 * 持久化在各服务器的 ~/.justserving/apps.json（单一事实源在服务器上），本机不保存副本。
 */
public class ManagedApp {
    public enum RunMode { DETACHED, SYSTEMD }

    public enum SourceType { GIT, UPLOAD }

    public String id = "";
    public String name = "";
    public String deployDir = "";
    public RunMode runMode = RunMode.DETACHED;

    public String startCommand = "";
    /** 停止命令（可选）：填写则停止时优先执行，否则后台进程模式按 PID 文件杀进程组。 */
    public String stopCommand = "";

    /** 保存时由 AppManager 展开为绝对路径；stdin 默认 /dev/null，输出默认 ~/.justserving/logs/<id>.*.log。 */
    public String stdinPath = "/dev/null";
    public String stdoutPath = "";
    public String stderrPath = "";

    /** 环境变量，每行一条 KEY=VALUE。 */
    public List<String> env = new ArrayList<>();

    public SourceType sourceType = SourceType.UPLOAD;
    public String gitUrl = "";
    public String gitBranch = "";
    /** none=匿名或使用服务器自身凭证；token=HTTPS 令牌（只存本机保险库，不落服务器）。 */
    public String gitAuth = "none";
    /** 仅 systemd 模式：开机自启（systemctl enable）。 */
    public boolean autoStart = false;

    /** 部署目录之外、随应用一起下载到本地的数据路径。 */
    public List<String> extraPaths = new ArrayList<>();

    public String createdAt = "";
    public String updatedAt = "";

    public String runModeText() {
        return runMode == RunMode.DETACHED ? "后台进程" : "systemd";
    }

    public String sourceText() {
        if (sourceType == SourceType.GIT) {
            String b = gitBranch == null || gitBranch.isBlank() ? "" : " @" + gitBranch;
            return "git" + b;
        }
        return "上传";
    }
}
