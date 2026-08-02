package com.linuxdesk.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Tracks the single most recent deploy backup per (host, remoteRoot) target, so "one-click
 * rollback" knows what to restore. Only the immediately-previous deployment is kept — recording
 * a new backup for the same target overwrites the old record (full history is a later increment).
 */
public class DeployBackupStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".linuxdesk");
    private static final Path STORE_FILE = STORE_DIR.resolve("deploy-backups.properties");

    public void record(String host, String remoteRoot, String backupPath, long timestamp) {
        String id = keyFor(host, remoteRoot);
        Properties props = readProperties();
        props.setProperty(id + ".host", host);
        props.setProperty(id + ".remoteRoot", remoteRoot);
        props.setProperty(id + ".backupPath", backupPath);
        props.setProperty(id + ".timestamp", String.valueOf(timestamp));
        writeQuietly(props);
    }

    public Optional<DeployBackupRecord> find(String host, String remoteRoot) {
        String id = keyFor(host, remoteRoot);
        Properties props = readProperties();
        String backupPath = props.getProperty(id + ".backupPath");
        if (backupPath == null) {
            return Optional.empty();
        }
        long timestamp = parseLong(props.getProperty(id + ".timestamp", "0"));
        return Optional.of(new DeployBackupRecord(host, remoteRoot, backupPath, timestamp));
    }

    private static String keyFor(String host, String remoteRoot) {
        return Integer.toHexString((host + "|" + remoteRoot).hashCode());
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
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
                props.store(out, "LinuxDesk deploy backup pointers (one entry per host+remote target)");
            }
        } catch (IOException e) {
            // Best-effort: a failed write just leaves the previous record in place.
        }
    }
}
