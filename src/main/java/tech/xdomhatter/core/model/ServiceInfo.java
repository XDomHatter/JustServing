package tech.xdomhatter.core.model;

/** 一个 systemd 服务单元的状态（由 systemctl list-units / list-unit-files 合并而来）。 */
public class ServiceInfo {
    public String unit = "";
    public String load = "";
    public String active = "";
    public String sub = "";
    public String enabled = "";
    public String description = "";

    public boolean running() {
        return active != null && !active.isBlank() && !active.equals("inactive") && !active.equals("failed");
    }

    public String stateText() {
        String a = active == null || active.isBlank() ? "-" : active;
        String s = sub == null || sub.isBlank() ? "-" : sub;
        return a + "/" + s;
    }

    /** 排序：运行中的在前，其余按单元名。 */
    public int rank() {
        if (running()) return 0;
        if (active != null && active.equals("failed")) return 1;
        return 2;
    }
}
