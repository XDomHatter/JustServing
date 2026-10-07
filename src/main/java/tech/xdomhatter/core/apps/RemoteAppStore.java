package tech.xdomhatter.core.apps;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import tech.xdomhatter.core.model.ManagedApp;
import tech.xdomhatter.core.sftp.SftpOps;
import tech.xdomhatter.core.util.Json;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 服务器端应用注册表：读写远端 ~/.justserving/apps.json。
 * 单一事实源在服务器上——同一台机器无论从哪台电脑连接都看到同一份应用列表。
 */
public class RemoteAppStore {
    public static final String DIR = ".justserving";
    public static final String FILE = ".justserving/apps.json";

    public static final class State {
        public int version = 1;
        public ArrayList<ManagedApp> apps = new ArrayList<>();
    }

    /** 读取应用列表；文件不存在或为空时返回空注册表。 */
    public State load(Session s) throws Exception {
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect();
        try {
            String path = c.realpath(".") + "/" + FILE;
            try (InputStream in = c.get(path)) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (SftpException notExists) {
                return new State();
            }
        } finally {
            c.disconnect();
        }
    }

    /** 原子保存：写 .tmp 再改名，权限 600（环境变量可能含敏感值）。 */
    public void save(Session s, State state) throws Exception {
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect();
        try {
            String home = c.realpath(".");
            String path = home + "/" + FILE;
            SftpOps.mkdirp(c, home + "/" + DIR);
            String tmp = path + ".tmp";
            try (InputStream in = new ByteArrayInputStream(serialize(state).getBytes(StandardCharsets.UTF_8))) {
                c.put(in, tmp);
            }
            try {
                c.rm(path);
            } catch (SftpException ignored) {
            }
            c.rename(tmp, path);
            c.chmod(0600, path);
        } finally {
            c.disconnect();
        }
    }

    // ---------- 解析（纯函数，便于测试） ----------

    public static State parse(String json) {
        State st = json == null || json.isBlank() ? null : Json.GSON.fromJson(json, State.class);
        if (st == null) st = new State();
        if (st.apps == null) st.apps = new ArrayList<>();
        st.apps.removeIf(a -> a == null || a.id == null || a.id.isBlank());
        st.apps.replaceAll(RemoteAppStore::normalize);
        return st;
    }

    public static String serialize(State state) {
        return Json.GSON.toJson(state);
    }

    /** 缺省与空值兜底，向后兼容旧版本文件。 */
    public static ManagedApp normalize(ManagedApp a) {
        if (a.name == null) a.name = "";
        if (a.deployDir == null) a.deployDir = "";
        if (a.runMode == null) a.runMode = ManagedApp.RunMode.DETACHED;
        if (a.startCommand == null) a.startCommand = "";
        if (a.deployCommand == null) a.deployCommand = "";
        if (a.stopCommand == null) a.stopCommand = "";
        if (a.stdinPath == null || a.stdinPath.isBlank()) a.stdinPath = "/dev/null";
        if (a.stdoutPath == null) a.stdoutPath = "";
        if (a.stderrPath == null) a.stderrPath = "";
        if (a.env == null) a.env = new ArrayList<>();
        a.env.removeIf(e -> e == null || e.isBlank() || !e.contains("="));
        if (a.sourceType == null) a.sourceType = ManagedApp.SourceType.UPLOAD;
        if (a.gitUrl == null) a.gitUrl = "";
        if (a.gitBranch == null) a.gitBranch = "";
        if (a.gitAuth == null) a.gitAuth = "none";
        if (a.extraPaths == null) a.extraPaths = new ArrayList<>();
        a.extraPaths.removeIf(p -> p == null || p.isBlank());
        if (a.createdAt == null) a.createdAt = "";
        if (a.updatedAt == null) a.updatedAt = "";
        return a;
    }
}
