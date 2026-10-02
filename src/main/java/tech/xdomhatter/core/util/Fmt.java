package tech.xdomhatter.core.util;

public final class Fmt {
    private Fmt() {}

    public static String bytes(long b) {
        if (b < 0) return "-";
        double v = b;
        String[] u = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return i == 0 ? (long) v + " " + u[i] : String.format("%.1f %s", v, u[i]);
    }

    public static String speed(double bytesPerSec) {
        if (bytesPerSec < 0) return "-";
        return bytes((long) bytesPerSec) + "/s";
    }

    public static String pct(double p) {
        return String.format("%.1f%%", p);
    }

    public static String bar(String label, double pct, int width) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        int filled = (int) Math.round(pct / 100 * width);
        StringBuilder sb = new StringBuilder(label).append(" [");
        sb.append("█".repeat(filled));
        sb.append("░".repeat(Math.max(0, width - filled)));
        sb.append("] ").append(String.format("%.1f%%", pct));
        return sb.toString();
    }

    public static String duration(long seconds) {
        if (seconds < 0) return "-";
        long d = seconds / 86400, h = seconds % 86400 / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        if (d > 0) return d + "天" + h + "小时";
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分" + s + "秒";
        return s + "秒";
    }
}
