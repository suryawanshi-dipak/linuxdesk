package com.linuxdesk.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Tracks the most recent deploy backups per (host, remoteRoot) target, newest first, capped at
 * {@link #MAX_RETAINED} — matching the SRS §7.6 mockup's "keep last 5". Recording a new backup
 * beyond the cap drops the oldest record; the caller is responsible for also deleting that
 * backup's remote archive file (returned so it can).
 */
public class DeployBackupStore {

    public static final int MAX_RETAINED = 5;

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".linuxdesk");
    private static final Path STORE_FILE = STORE_DIR.resolve("deploy-backups.properties");

    /** Adds a new backup as the newest for this target. Returns any records dropped past the retention cap. */
    public List<DeployBackupRecord> record(String host, String remoteRoot, String backupPath, long timestamp) {
        String id = keyFor(host, remoteRoot);
        List<DeployBackupRecord> current = new ArrayList<>(list(host, remoteRoot));
        current.add(0, new DeployBackupRecord(host, remoteRoot, backupPath, timestamp));

        List<DeployBackupRecord> dropped = new ArrayList<>();
        while (current.size() > MAX_RETAINED) {
            dropped.add(current.remove(current.size() - 1));
        }

        Properties props = readProperties();
        clearTarget(props, id);
        props.setProperty(id + ".host", host);
        props.setProperty(id + ".remoteRoot", remoteRoot);
        props.setProperty(id + ".count", String.valueOf(current.size()));
        for (int i = 0; i < current.size(); i++) {
            DeployBackupRecord entry = current.get(i);
            props.setProperty(id + "." + i + ".backupPath", entry.backupPath());
            props.setProperty(id + "." + i + ".timestamp", String.valueOf(entry.timestamp()));
        }
        writeQuietly(props);
        return dropped;
    }

    /** All retained backups for this target, newest first. Empty if none. */
    public List<DeployBackupRecord> list(String host, String remoteRoot) {
        String id = keyFor(host, remoteRoot);
        Properties props = readProperties();
        int count = parseInt(props.getProperty(id + ".count", "0"));
        List<DeployBackupRecord> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String backupPath = props.getProperty(id + "." + i + ".backupPath");
            if (backupPath == null) {
                continue;
            }
            long timestamp = parseLong(props.getProperty(id + "." + i + ".timestamp", "0"));
            result.add(new DeployBackupRecord(host, remoteRoot, backupPath, timestamp));
        }
        return result;
    }

    public Optional<DeployBackupRecord> latest(String host, String remoteRoot) {
        List<DeployBackupRecord> all = list(host, remoteRoot);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private static void clearTarget(Properties props, String id) {
        int oldCount = parseInt(props.getProperty(id + ".count", "0"));
        for (int i = 0; i < oldCount; i++) {
            props.remove(id + "." + i + ".backupPath");
            props.remove(id + "." + i + ".timestamp");
        }
    }

    private static String keyFor(String host, String remoteRoot) {
        return Integer.toHexString((host + "|" + remoteRoot).hashCode());
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
                props.store(out, "LinuxDesk deploy backups, newest first, capped at " + MAX_RETAINED + " per target");
            }
        } catch (IOException e) {
            // Best-effort: a failed write just leaves the previous records in place.
        }
    }
}
