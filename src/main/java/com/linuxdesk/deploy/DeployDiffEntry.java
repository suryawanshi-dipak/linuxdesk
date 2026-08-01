package com.linuxdesk.deploy;

/**
 * One file's comparison result between a local directory tree and its remote deployment target.
 * {@code localSize}/{@code localModifiedMillis} are -1 when the file doesn't exist locally
 * (REMOTE_ONLY); {@code remoteSize}/{@code remoteModifiedMillis} are -1 when it doesn't exist
 * remotely (LOCAL_ONLY).
 */
public record DeployDiffEntry(String relativePath, Status status,
                               long localSize, long localModifiedMillis,
                               long remoteSize, long remoteModifiedMillis) {

    public enum Status {
        IDENTICAL,
        MODIFIED,
        LOCAL_ONLY,
        REMOTE_ONLY
    }
}
