package tech.xdomhatter.core.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppPaths {
    public static final Path DIR = base();
    public static final Path CONFIG = DIR.resolve("config.json");
    public static final Path VAULT = DIR.resolve("vault.json");
    public static final Path KNOWN_HOSTS = DIR.resolve("known_hosts");
    public static final Path FRP_DIR = DIR.resolve("frp");
    public static final Path FRP_CONF = FRP_DIR.resolve("conf");
    public static final Path LOG_DIR = DIR.resolve("logs");

    static {
        mkdirs(DIR, FRP_CONF, LOG_DIR);
        try {
            if (Files.notExists(KNOWN_HOSTS)) Files.writeString(KNOWN_HOSTS, "");
        } catch (IOException ignored) {
        }
    }

    private AppPaths() {}

    public static String frpcDefault() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return FRP_DIR.resolve(win ? "frpc.exe" : "frpc").toString();
    }

    private static Path base() {
        String custom = System.getProperty("justserving.home");
        return custom != null && !custom.isBlank()
                ? Path.of(custom)
                : Path.of(System.getProperty("user.home"), ".justserving");
    }

    private static void mkdirs(Path... ps) {
        for (Path p : ps) {
            try {
                Files.createDirectories(p);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
