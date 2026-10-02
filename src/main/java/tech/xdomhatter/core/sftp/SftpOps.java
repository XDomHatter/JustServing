package tech.xdomhatter.core.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;

public final class SftpOps {
    private SftpOps() {}

    public record Entry(String name, boolean dir, long size, long mtime) {}

    public static List<Entry> list(ChannelSftp c, String dir) throws SftpException {
        Vector<ChannelSftp.LsEntry> v = c.ls(dir);
        List<Entry> out = new ArrayList<>();
        for (ChannelSftp.LsEntry e : v) {
            String n = e.getFilename();
            if (n.equals(".") || n.equals("..")) continue;
            SftpATTRS a = e.getAttrs();
            out.add(new Entry(n, a.isDir() && !a.isLink(), a.getSize(), a.getMTime() * 1000L));
        }
        out.sort(Comparator.comparing((Entry x) -> !x.dir()).thenComparing(x -> x.name().toLowerCase()));
        return out;
    }

    public static String home(ChannelSftp c) throws SftpException {
        return c.realpath(".");
    }

    public static void mkdirp(ChannelSftp c, String path) throws SftpException {
        String[] parts = path.split("/");
        String cur = path.startsWith("/") ? "" : ".";
        for (String p : parts) {
            if (p.isEmpty()) continue;
            cur = cur.isEmpty() ? "/" + p : (cur.equals(".") ? p : cur + "/" + p);
            try {
                c.stat(cur);
            } catch (SftpException e) {
                c.mkdir(cur);
            }
        }
    }

    public static void deleteRecursive(ChannelSftp c, String path) throws SftpException {
        SftpATTRS a = c.stat(path);
        if (a.isDir() && !a.isLink()) {
            for (ChannelSftp.LsEntry e : c.ls(path)) {
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                deleteRecursive(c, path + "/" + n);
            }
            c.rmdir(path);
        } else {
            c.rm(path);
        }
    }

    public static String nameOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    public static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }
}
