package tech.xdomhatter.core.model;

public class FrpProxy {
    public String id;
    public String profileId;
    public String name = "";
    public String type = "tcp";
    public String localIp = "127.0.0.1";
    public int localPort = 80;
    public int remotePort = 6080;
    public boolean enabled = true;
}
