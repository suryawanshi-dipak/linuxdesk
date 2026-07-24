package com.linuxdesk.model;

public class ConnectionProfile {

    private String host = "";
    private int port = 22;
    private String username = "";
    private String privateKeyPath = "";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String toSshCommand() {
        StringBuilder sb = new StringBuilder("ssh");
        if (privateKeyPath != null && !privateKeyPath.isBlank()) {
            sb.append(" -i ").append(privateKeyPath);
        }
        if (port != 22) {
            sb.append(" -p ").append(port);
        }
        sb.append(" ").append(username.isBlank() ? "user" : username)
          .append("@").append(host.isBlank() ? "host" : host);
        return sb.toString();
    }
}
