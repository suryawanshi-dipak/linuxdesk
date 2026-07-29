package com.linuxdesk.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Local, append-only, hash-chained audit trail of destructive/administrative actions LinuxDesk
 * performs (delete, chmod/chown, service control, kill process, connect/disconnect). Never logs
 * secrets. Each line hashes its own fields plus the previous line's hash, so editing or deleting
 * an earlier line breaks the chain from that point on — detectable via {@link #verifyChain()}.
 *
 * Fields are Base64-encoded per line (not JSON/CSV) purely to sidestep delimiter-escaping bugs;
 * this trades the SRS's "exportable as CSV/JSON" nice-to-have for a much simpler, safer format.
 */
public class AuditLogStore {

    private static final Path STORE_DIR = Path.of(System.getProperty("user.home"), ".linuxdesk");
    private static final Path LOG_FILE = STORE_DIR.resolve("audit.log");
    static final String GENESIS_HASH = "0".repeat(64);

    public synchronized void record(String host, String remoteUser, String action, String outcome, String detail) {
        String safeDetail = detail == null ? "" : detail;
        String prevHash = lastHashOf(loadAll());
        long timestamp = System.currentTimeMillis();
        String hash = computeHash(timestamp, host, remoteUser, action, outcome, safeDetail, prevHash);
        appendLine(encodeLine(timestamp, host, remoteUser, action, outcome, safeDetail, prevHash, hash));
    }

    public synchronized List<AuditLogEntry> loadAll() {
        List<AuditLogEntry> entries = new ArrayList<>();
        if (!Files.exists(LOG_FILE)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(LOG_FILE, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    decodeLine(line).ifPresent(entries::add);
                }
            }
        } catch (IOException e) {
            // Treat an unreadable log as empty rather than failing the viewer.
        }
        return entries;
    }

    /** Recomputes every entry's hash from its fields + prevHash and checks the chain is unbroken. */
    public synchronized boolean verifyChain() {
        String expectedPrev = GENESIS_HASH;
        for (AuditLogEntry entry : loadAll()) {
            if (!entry.prevHash().equals(expectedPrev)) {
                return false;
            }
            String recomputed = computeHash(entry.timestamp(), entry.host(), entry.remoteUser(), entry.action(),
                    entry.outcome(), entry.detail(), entry.prevHash());
            if (!recomputed.equals(entry.hash())) {
                return false;
            }
            expectedPrev = entry.hash();
        }
        return true;
    }

    private static String lastHashOf(List<AuditLogEntry> entries) {
        return entries.isEmpty() ? GENESIS_HASH : entries.get(entries.size() - 1).hash();
    }

    private static String computeHash(long timestamp, String host, String remoteUser, String action,
                                       String outcome, String detail, String prevHash) {
        String payload = timestamp + "|" + host + "|" + remoteUser + "|" + action + "|" + outcome + "|" + detail + "|" + prevHash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void appendLine(String line) {
        try {
            Files.createDirectories(STORE_DIR);
            Files.writeString(LOG_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Best-effort: a failed write just means this one action wasn't recorded.
        }
    }

    private static String encodeLine(long timestamp, String host, String remoteUser, String action,
                                      String outcome, String detail, String prevHash, String hash) {
        Base64.Encoder b64 = Base64.getEncoder();
        return timestamp + "|"
                + b64.encodeToString(host.getBytes(StandardCharsets.UTF_8)) + "|"
                + b64.encodeToString(remoteUser.getBytes(StandardCharsets.UTF_8)) + "|"
                + b64.encodeToString(action.getBytes(StandardCharsets.UTF_8)) + "|"
                + b64.encodeToString(outcome.getBytes(StandardCharsets.UTF_8)) + "|"
                + b64.encodeToString(detail.getBytes(StandardCharsets.UTF_8)) + "|"
                + prevHash + "|" + hash;
    }

    private static Optional<AuditLogEntry> decodeLine(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 8) {
            return Optional.empty();
        }
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            long timestamp = Long.parseLong(parts[0]);
            String host = new String(b64.decode(parts[1]), StandardCharsets.UTF_8);
            String remoteUser = new String(b64.decode(parts[2]), StandardCharsets.UTF_8);
            String action = new String(b64.decode(parts[3]), StandardCharsets.UTF_8);
            String outcome = new String(b64.decode(parts[4]), StandardCharsets.UTF_8);
            String detail = new String(b64.decode(parts[5]), StandardCharsets.UTF_8);
            String prevHash = parts[6];
            String hash = parts[7];
            return Optional.of(new AuditLogEntry(timestamp, host, remoteUser, action, outcome, detail, prevHash, hash));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
