package com.linuxdesk.model;

import java.util.UUID;

public class ConnectionProfile {

    public static final String DEFAULT_COLOR = "#8b93a3";

    public enum AuthMethod {
        PRIVATE_KEY,
        PASSWORD
    }

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String host = "";
    private int port = 22;
    private String username = "";
    private AuthMethod authMethod = AuthMethod.PRIVATE_KEY;
    private String privateKeyPath = "";
    private String colorTag = DEFAULT_COLOR;
    private boolean production;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

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

    public AuthMethod getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(AuthMethod authMethod) {
        this.authMethod = authMethod == null ? AuthMethod.PRIVATE_KEY : authMethod;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getColorTag() {
        return colorTag;
    }

    public void setColorTag(String colorTag) {
        this.colorTag = colorTag;
    }

    public boolean isProduction() {
        return production;
    }

    public void setProduction(boolean production) {
        this.production = production;
    }

    /** Display name for lists/pickers: falls back to host@user when no name was given. */
    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return (username == null || username.isBlank() ? "user" : username)
                + "@" + (host == null || host.isBlank() ? "host" : host);
    }

    public ConnectionProfile copyAsNew(String newName) {
        ConnectionProfile copy = new ConnectionProfile();
        copy.setName(newName);
        copy.setHost(host);
        copy.setPort(port);
        copy.setUsername(username);
        copy.setAuthMethod(authMethod);
        copy.setPrivateKeyPath(privateKeyPath);
        copy.setColorTag(colorTag);
        copy.setProduction(production);
        return copy;
    }

    public String toSshCommand() {
        StringBuilder sb = new StringBuilder("ssh");
        if (authMethod == AuthMethod.PRIVATE_KEY && privateKeyPath != null && !privateKeyPath.isBlank()) {
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
