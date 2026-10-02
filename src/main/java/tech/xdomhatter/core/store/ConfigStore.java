package tech.xdomhatter.core.store;

import com.google.gson.JsonSyntaxException;
import tech.xdomhatter.core.model.AppConfig;
import tech.xdomhatter.core.model.CommandTask;
import tech.xdomhatter.core.model.FrpProxy;
import tech.xdomhatter.core.model.OpenBinding;
import tech.xdomhatter.core.model.SshProfile;
import tech.xdomhatter.core.model.TunnelSpec;
import tech.xdomhatter.core.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class ConfigStore {
    private final Path file;
    private final AppConfig cfg;

    public ConfigStore(Path file) {
        this.file = file;
        this.cfg = load();
    }

    public AppConfig get() {
        return cfg;
    }

    public void save() {
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.GSON.toJson(cfg), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存配置失败: " + file, e);
        }
    }

    private AppConfig load() {
        try {
            String s = Files.readString(file, StandardCharsets.UTF_8);
            AppConfig a = Json.GSON.fromJson(s, AppConfig.class);
            if (a != null) {
                return normalize(a);
            }
        } catch (NoSuchFileException | JsonSyntaxException ignored) {
        } catch (IOException ignored) {
        }
        return new AppConfig();
    }

    private static AppConfig normalize(AppConfig a) {
        if (a.profiles == null) a.profiles = new java.util.ArrayList<>();
        if (a.bindings == null) a.bindings = new java.util.ArrayList<>();
        if (a.tunnels == null) a.tunnels = new java.util.ArrayList<>();
        if (a.frpProxies == null) a.frpProxies = new java.util.ArrayList<>();
        if (a.commandTasks == null) a.commandTasks = new java.util.ArrayList<>();
        if (a.settings == null) a.settings = new AppConfig.Settings();
        return a;
    }

    public Optional<SshProfile> profile(String id) {
        return cfg.profiles.stream().filter(p -> p.id.equals(id)).findFirst();
    }

    public void removeProfile(String id) {
        cfg.profiles.removeIf(p -> p.id.equals(id));
        cfg.tunnels.removeIf(t -> t.profileId.equals(id));
        cfg.frpProxies.removeIf(p -> p.profileId.equals(id));
        cfg.commandTasks.removeIf(t -> t.profileId.equals(id));
        save();
    }

    /** 某服务器可用的命令任务：绑定该机的 + 全局任务。 */
    public List<CommandTask> tasksFor(String profileId) {
        return cfg.commandTasks.stream()
                .filter(t -> t.profileId == null || t.profileId.isEmpty() || t.profileId.equals(profileId))
                .toList();
    }

    public List<TunnelSpec> tunnelsOf(String profileId) {
        return cfg.tunnels.stream().filter(t -> t.profileId.equals(profileId)).toList();
    }

    public List<FrpProxy> proxiesOf(String profileId) {
        return cfg.frpProxies.stream().filter(p -> p.profileId.equals(profileId)).toList();
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
