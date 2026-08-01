package com.linuxdesk.profile;

import com.linuxdesk.model.ConnectionHistoryEntry;
import com.linuxdesk.model.ConnectionProfile.AuthMethod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persists the most recent successful connections (host/port/username/key path/timestamp,
 * never the passphrase) to {@code ~/.linuxdesk/history.properties}, newest first, capped at
 * {@link #MAX_ENTRIES}. Reconnecting to the same host/port/username replaces the older entry
 * rather than duplicating it.
 */
public class ConnectionHistoryStore {

    private static final int MAX_ENTRIES = 20;
    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".linuxdesk");
    private static final Path STORE_FILE = STORE_DIR.resolve("history.properties");

    public synchronized List<ConnectionHistoryEntry> loadAll() {
        Properties props = readProperties();
        int count = parseInt(props.getProperty("count", "0"));
        List<ConnectionHistoryEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(new ConnectionHistoryEntry(
                    props.getProperty(i + ".host", ""),
                    parseInt(props.getProperty(i + ".port", "22")),
                    props.getProperty(i + ".username", ""),
                    parseAuthMethod(props.getProperty(i + ".authMethod", "")),
                    props.getProperty(i + ".privateKeyPath", ""),
                    blankToNull(props.getProperty(i + ".profileName", "")),
                    parseLong(props.getProperty(i + ".timestamp", "0"))));
        }
        return entries;
    }

    public synchronized void record(ConnectionHistoryEntry entry) {
        List<ConnectionHistoryEntry> entries = new ArrayList<>(loadAll());
        entries.removeIf(e -> matches(e, entry));
        entries.add(0, entry);
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        writeAll(entries);
    }

    public synchronized void remove(ConnectionHistoryEntry entry) {
        List<ConnectionHistoryEntry> entries = new ArrayList<>(loadAll());
        entries.removeIf(e -> matches(e, entry));
        writeAll(entries);
    }

    public synchronized void clearAll() {
        writeAll(List.of());
    }

    private boolean matches(ConnectionHistoryEntry a, ConnectionHistoryEntry b) {
        return a.host().equalsIgnoreCase(b.host()) && a.port() == b.port() && a.username().equals(b.username());
    }

    private void writeAll(List<ConnectionHistoryEntry> entries) {
        Properties props = new Properties();
        props.setProperty("count", String.valueOf(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            ConnectionHistoryEntry e = entries.get(i);
            props.setProperty(i + ".host", e.host());
            props.setProperty(i + ".port", String.valueOf(e.port()));
            props.setProperty(i + ".username", e.username());
            props.setProperty(i + ".authMethod", e.authMethod().name());
            props.setProperty(i + ".privateKeyPath", e.privateKeyPath() == null ? "" : e.privateKeyPath());
            props.setProperty(i + ".profileName", e.profileName() == null ? "" : e.profileName());
            props.setProperty(i + ".timestamp", String.valueOf(e.timestamp()));
        }
        writeQuietly(props);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static AuthMethod parseAuthMethod(String value) {
        try {
            return AuthMethod.valueOf(value);
        } catch (IllegalArgumentException e) {
            return AuthMethod.PRIVATE_KEY;
        }
    }

    private Properties readProperties() {
        Properties props = new Properties();
        if (Files.exists(STORE_FILE)) {
            try (InputStream in = Files.newInputStream(STORE_FILE)) {
                props.load(in);
            } catch (IOException e) {
                // Treat an unreadable store as empty rather than failing.
            }
        }
        return props;
    }

    private void writeQuietly(Properties props) {
        try {
            Files.createDirectories(STORE_DIR);
            try (OutputStream out = Files.newOutputStream(STORE_FILE)) {
                props.store(out, "LinuxDesk connection history (passphrase is never stored)");
            }
        } catch (IOException e) {
            // Best-effort: a failed write just leaves the previous history in place.
        }
    }
}
