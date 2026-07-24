package com.linuxdesk.profile;

import com.linuxdesk.model.ConnectionProfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists connection details (never the passphrase) so the login form
 * can be prefilled next time the app starts.
 */
public class ProfileStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".linuxdesk");
    private static final Path STORE_FILE = STORE_DIR.resolve("profile.properties");

    public ConnectionProfile load() {
        ConnectionProfile profile = new ConnectionProfile();
        if (!Files.exists(STORE_FILE)) {
            return profile;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(STORE_FILE)) {
            props.load(in);
        } catch (IOException e) {
            return profile;
        }
        profile.setHost(props.getProperty("host", ""));
        profile.setPort(parsePort(props.getProperty("port", "22")));
        profile.setUsername(props.getProperty("username", ""));
        profile.setPrivateKeyPath(props.getProperty("privateKeyPath", ""));
        return profile;
    }

    public void save(ConnectionProfile profile) throws IOException {
        Files.createDirectories(STORE_DIR);
        Properties props = new Properties();
        props.setProperty("host", profile.getHost());
        props.setProperty("port", String.valueOf(profile.getPort()));
        props.setProperty("username", profile.getUsername());
        props.setProperty("privateKeyPath", profile.getPrivateKeyPath());
        try (OutputStream out = Files.newOutputStream(STORE_FILE)) {
            props.store(out, "LinuxDesk saved connection (passphrase is never stored)");
        }
    }

    private int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 22;
        }
    }
}
