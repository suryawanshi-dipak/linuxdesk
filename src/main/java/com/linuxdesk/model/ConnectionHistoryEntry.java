package com.linuxdesk.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One successful past connection, used to populate the login screen's "Recent" list.
 * {@code privateKeyPath} is stored (it's just a filesystem path) so a history entry can be
 * reconnected to standalone, without requiring a matching saved profile.
 */
public record ConnectionHistoryEntry(
        String host, int port, String username, ConnectionProfile.AuthMethod authMethod, String privateKeyPath,
        String profileName, long timestamp) {

    public String displayLabel() {
        return (username == null || username.isBlank() ? "user" : username)
                + "@" + (host == null || host.isBlank() ? "host" : host);
    }

    public String timeAgo() {
        Duration elapsed = Duration.between(Instant.ofEpochMilli(timestamp), Instant.now());
        long minutes = elapsed.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = elapsed.toHours();
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = elapsed.toDays();
        if (days < 30) {
            return days + "d ago";
        }
        return (days / 30) + "mo ago";
    }
}
