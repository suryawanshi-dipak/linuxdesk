package com.linuxdesk.audit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** One recorded action: what happened, against which host, and whether it succeeded. */
public record AuditLogEntry(
        long timestamp, String host, String remoteUser, String action, String outcome, String detail,
        String prevHash, String hash) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public String formattedTime() {
        return TIME_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }
}
