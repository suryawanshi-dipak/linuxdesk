package com.linuxdesk.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Persists recently visited remote directories and recently opened remote files per host
 * (keyed by {@code username@host}), capped at {@link #MAX_ENTRIES} each, most-recent-first.
 * Stored at {@code ~/.linuxdesk/recent.properties}.
 */
public class RecentPathsStore {

    private static final int MAX_ENTRIES = 15;
    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".linuxdesk");
    private static final Path STORE_FILE = STORE_DIR.resolve("recent.properties");

    public synchronized List<String> loadDirectories(String hostKey) {
        return loadList(readProperties(), hostKey + ".dirs");
    }

    public synchronized List<String> loadFiles(String hostKey) {
        return loadList(readProperties(), hostKey + ".files");
    }

    public synchronized void recordDirectory(String hostKey, String path) {
        recordInto(hostKey + ".dirs", path);
    }

    public synchronized void recordFile(String hostKey, String path) {
        recordInto(hostKey + ".files", path);
    }

    public synchronized void clear(String hostKey) {
        Properties props = readProperties();
        props.remove(hostKey + ".dirs");
        props.remove(hostKey + ".files");
        writeQuietly(props);
    }

    private void recordInto(String key, String path) {
        Properties props = readProperties();
        List<String> list = loadList(props, key);
        list.remove(path);
        list.add(0, path);
        if (list.size() > MAX_ENTRIES) {
            list = new ArrayList<>(list.subList(0, MAX_ENTRIES));
        }
        props.setProperty(key, String.join("\n", list));
        writeQuietly(props);
    }

    private List<String> loadList(Properties props, String key) {
        String raw = props.getProperty(key, "");
        if (raw.isBlank()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(raw.split("\n")));
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
                props.store(out, "LinuxDesk recently visited folders/files per host");
            }
        } catch (IOException e) {
            // Best-effort: a failed write just leaves the previous list in place.
        }
    }
}
