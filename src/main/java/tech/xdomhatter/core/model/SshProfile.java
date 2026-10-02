package tech.xdomhatter.core.model;

public class SshProfile {
    public enum AuthType { PASSWORD, KEY }

    public String id;
    public String name = "";
    public String host = "";
    public int port = 22;
    public String user = "root";
    public AuthType authType = AuthType.PASSWORD;
    public String keyPath = "";
    public long lastUsed;

    public String secretKey() {
        return "cred." + id + ".password";
    }

    public String keyPassKey() {
        return "cred." + id + ".keypass";
    }

    public String display() {
        return name + " (" + user + "@" + host + ":" + port + ")";
    }
}
