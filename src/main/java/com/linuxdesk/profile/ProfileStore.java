package com.linuxdesk.profile;

import com.linuxdesk.model.ConnectionProfile;
import com.linuxdesk.model.ConnectionProfile.AuthMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Persists named connection profiles (never the passphrase) to a single properties file:
 * {@code order} holds the display order as a comma-separated list of profile ids, and every
 * other field is stored under an {@code <id>.<field>} key.
 *
 * Transparently migrates the old single-profile store (~/.linuxdesk/profile.properties, from
 * before multi-profile support) into a "Default" profile on first load.
 */
public class ProfileStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".linuxdesk");
    private static final Path STORE_FILE = STORE_DIR.resolve("profiles.properties");
    private static final Path LEGACY_FILE = STORE_DIR.resolve("profile.properties");

    public List<ConnectionProfile> loadAll() {
        migrateLegacyProfileIfNeeded();

        Properties props = readProperties();
        List<ConnectionProfile> profiles = new ArrayList<>();
        for (String id : orderedIds(props)) {
            profiles.add(toProfile(props, id));
        }
        return profiles;
    }

    public String loadLastUsedId() {
        return readProperties().getProperty("lastUsedId", "");
    }

    public void setLastUsedId(String id) {
        Properties props = readProperties();
        props.setProperty("lastUsedId", id);
        writeQuietly(props);
    }

    public void save(ConnectionProfile profile) throws IOException {
        Properties props = readProperties();
        Set<String> order = new LinkedHashSet<>(orderedIds(props));
        order.add(profile.getId());
        props.setProperty("order", String.join(",", order));

        String id = profile.getId();
        props.setProperty(id + ".name", nullToEmpty(profile.getName()));
        props.setProperty(id + ".host", nullToEmpty(profile.getHost()));
        props.setProperty(id + ".port", String.valueOf(profile.getPort()));
        props.setProperty(id + ".username", nullToEmpty(profile.getUsername()));
        props.setProperty(id + ".authMethod", profile.getAuthMethod().name());
        props.setProperty(id + ".privateKeyPath", nullToEmpty(profile.getPrivateKeyPath()));
        props.setProperty(id + ".colorTag", nullToEmpty(profile.getColorTag()));
        props.setProperty(id + ".production", String.valueOf(profile.isProduction()));
        writeProperties(props);
    }

    public void delete(String id) {
        Properties props = readProperties();
        List<String> order = orderedIds(props);
        order.remove(id);
        props.setProperty("order", String.join(",", order));
        for (String field : new String[] {"name", "host", "port", "username", "authMethod", "privateKeyPath", "colorTag", "production"}) {
            props.remove(id + "." + field);
        }
        if (id.equals(props.getProperty("lastUsedId"))) {
            props.remove("lastUsedId");
        }
        writeQuietly(props);
    }

    private void migrateLegacyProfileIfNeeded() {
        if (Files.exists(STORE_FILE) || !Files.exists(LEGACY_FILE)) {
            return;
        }
        Properties legacy = new Properties();
        try (InputStream in = Files.newInputStream(LEGACY_FILE)) {
            legacy.load(in);
        } catch (IOException e) {
            return;
        }
        ConnectionProfile migrated = new ConnectionProfile();
        migrated.setName("Default");
        migrated.setHost(legacy.getProperty("host", ""));
        migrated.setPort(parsePort(legacy.getProperty("port", "22")));
        migrated.setUsername(legacy.getProperty("username", ""));
        migrated.setPrivateKeyPath(legacy.getProperty("privateKeyPath", ""));
        try {
            save(migrated);
            setLastUsedId(migrated.getId());
        } catch (IOException e) {
            // Best-effort migration; if it fails the user just starts with an empty profile list.
        }
    }

    private ConnectionProfile toProfile(Properties props, String id) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setId(id);
        profile.setName(props.getProperty(id + ".name", ""));
        profile.setHost(props.getProperty(id + ".host", ""));
        profile.setPort(parsePort(props.getProperty(id + ".port", "22")));
        profile.setUsername(props.getProperty(id + ".username", ""));
        profile.setAuthMethod(parseAuthMethod(props.getProperty(id + ".authMethod", "")));
        profile.setPrivateKeyPath(props.getProperty(id + ".privateKeyPath", ""));
        profile.setColorTag(props.getProperty(id + ".colorTag", ConnectionProfile.DEFAULT_COLOR));
        profile.setProduction(Boolean.parseBoolean(props.getProperty(id + ".production", "false")));
        return profile;
    }

    private List<String> orderedIds(Properties props) {
        String orderStr = props.getProperty("order", "");
        List<String> ids = new ArrayList<>();
        for (String id : orderStr.split(",")) {
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Properties readProperties() {
        Properties props = new Properties();
        if (Files.exists(STORE_FILE)) {
            try (InputStream in = Files.newInputStream(STORE_FILE)) {
                props.load(in);
            } catch (IOException e) {
                // Treat an unreadable store as empty rather than failing profile loading.
            }
        }
        return props;
    }

    private void writeProperties(Properties props) throws IOException {
        Files.createDirectories(STORE_DIR);
        try (OutputStream out = Files.newOutputStream(STORE_FILE)) {
            props.store(out, "LinuxDesk saved connection profiles (passphrase is never stored)");
        }
    }

    private void writeQuietly(Properties props) {
        try {
            writeProperties(props);
        } catch (IOException e) {
            // Best-effort: a failed delete/lastUsed update just leaves the file as-is.
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 22;
        }
    }

    private AuthMethod parseAuthMethod(String value) {
        try {
            return AuthMethod.valueOf(value);
        } catch (IllegalArgumentException e) {
            return AuthMethod.PRIVATE_KEY;
        }
    }
}
