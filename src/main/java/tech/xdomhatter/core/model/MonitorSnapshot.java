package tech.xdomhatter.core.model;

import java.util.ArrayList;
import java.util.List;

public class MonitorSnapshot {
    public long timestamp;
    public double cpuPercent = -1;
    public long memTotal = -1;
    public long memAvailable = -1;
    public long swapTotal = -1;
    public long swapFree = -1;
    public List<Disk> disks = new ArrayList<>();
    public List<PortListen> ports = new ArrayList<>();
    public List<Proc> procs = new ArrayList<>();
    public String error;

    public static class Disk {
        public String fs, mount;
        public long total, used;

        public int percent() {
            return total <= 0 ? 0 : (int) Math.min(100, used * 100 / total);
        }
    }

    public static class PortListen {
        public String proto, addr, process;
        public int port;
        public int pid = -1;
    }

    public static class Proc {
        public long pid;
        public String user, command, elapsed;
        public double cpu, mem;
        public long rssKb;
    }
}
