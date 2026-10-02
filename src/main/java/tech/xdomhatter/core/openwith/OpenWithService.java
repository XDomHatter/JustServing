package tech.xdomhatter.core.openwith;

import tech.xdomhatter.core.model.OpenBinding;
import tech.xdomhatter.core.store.ConfigStore;

import java.awt.Desktop;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 本机文件打开方式：扩展名模式（如 *.log / .txt / *）→ 命令模板（%f 为文件占位符）。
 * 按绑定顺序匹配，无命中时回退系统默认打开方式。
 */
public class OpenWithService {
    private final ConfigStore cfg;

    public OpenWithService(ConfigStore cfg) {
        this.cfg = cfg;
    }

    public Optional<OpenBinding> match(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (OpenBinding b : cfg.get().bindings) {
            if (!b.enabled) continue;
            String pat = b.pattern == null ? "" : b.pattern.toLowerCase(Locale.ROOT).strip();
            if (pat.isEmpty()) continue;
            if (pat.equals("*") || pat.equals("*.*")) return Optional.of(b);
            if (pat.startsWith("*")) {
                if (lower.endsWith(pat.substring(1))) return Optional.of(b);
            } else if (pat.startsWith(".")) {
                if (lower.endsWith(pat)) return Optional.of(b);
            } else if (lower.equals(pat)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    public void open(Path file) throws Exception {
        OpenBinding b = match(file.getFileName().toString()).orElse(null);
        if (b != null && b.command != null && !b.command.isBlank()) {
            String cmd = b.command.contains("%f")
                    ? b.command.replace("%f", quote(file.toString()))
                    : b.command + " " + quote(file.toString());
            new ProcessBuilder(splitCommand(cmd)).start();
        } else {
            Desktop.getDesktop().open(file.toFile());
        }
    }

    /** 用双引号包裹路径（供命令模板 %f 替换；文件名内含引号的情况极少，直接剔除）。 */
    public static String quote(String path) {
        return "\"" + path.replace("\"", "") + "\"";
    }

    /** 按空白切分命令，双引号内的空格不切分。 */
    public static String[] splitCommand(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        boolean hasToken = false;
        for (int i = 0; i < cmd.length(); i++) {
            char ch = cmd.charAt(i);
            if (ch == '"') {
                inQuote = !inQuote;
                hasToken = true;
            } else if (ch == ' ' && !inQuote) {
                if (cur.length() > 0 || hasToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0 || hasToken) out.add(cur.toString());
        return out.toArray(String[]::new);
    }
}
