package tech.xdomhatter.core.model;

public class TunnelSpec {
    public enum Type { LOCAL, REMOTE }

    public String id;
    public String profileId;
    public String name = "";
    public Type type = Type.REMOTE;
    public String bindAddr = "0.0.0.0";
    public int listenPort = 8080;
    public String targetHost = "127.0.0.1";
    public int targetPort = 80;
    public boolean autoStart = false;

    public String describe() {
        String listen = type == Type.REMOTE ? "服务器:" + listenPort : bindAddr + ":" + listenPort;
        return type + " " + listen + " → " + targetHost + ":" + targetPort;
    }
}
