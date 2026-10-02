package tech.xdomhatter.ui.swing;

import javax.swing.Icon;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 扩展名 → 文件类型图标（16x16 单色线条，跟随主题前景色）。实例缓存避免重复解析 SVG。 */
final class FileIcons {
    private static final Map<String, Icon> CACHE = new HashMap<>();

    private FileIcons() {}

    static Icon iconFor(String name, boolean dir) {
        String icon = dir ? "folder" : forExt(extOf(name));
        synchronized (CACHE) {
            Icon i = CACHE.get(icon);
            if (i == null) {
                i = Ui.icon(icon);
                CACHE.put(icon, i);
            }
            return i;
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String forExt(String ext) {
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "tif", "tiff", "psd", "heic" -> "image";
            case "mp3", "wav", "flac", "ogg", "oga", "m4a", "aac", "wma", "opus", "mid", "midi" -> "audio";
            case "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "mts", "3gp" -> "video";
            case "zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "7z", "rar", "zst", "iso", "jar", "war" -> "archive";
            case "java", "kt", "kts", "py", "js", "ts", "jsx", "tsx", "c", "h", "cpp", "hpp", "cc", "cs",
                 "go", "rs", "rb", "php", "pl", "lua", "sh", "bash", "zsh", "bat", "cmd", "ps1",
                 "html", "htm", "css", "scss", "xml", "json", "yml", "yaml", "toml", "ini", "conf", "cfg",
                 "sql", "gradle", "properties" -> "code";
            case "txt", "md", "markdown", "log", "rtf", "doc", "docx", "odt", "tex", "epub" -> "text";
            case "pdf" -> "pdf";
            case "xls", "xlsx", "xlsm", "ods", "csv", "tsv" -> "sheet";
            default -> "file";
        };
    }
}
