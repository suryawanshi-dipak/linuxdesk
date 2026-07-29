package com.linuxdesk.ssh;

/**
 * Called synchronously from the SSH connect thread whenever the host key needs a human
 * decision. Implementations must block until the user answers (e.g. via a JavaFX dialog
 * shown through {@code Platform.runLater} + a latch) since the SSH handshake is waiting
 * on the return value.
 */
public interface HostKeyPrompt {

    boolean confirmUnknownHost(String host, int port, String keyType, String sha256Fingerprint, String md5Fingerprint);

    boolean confirmChangedHost(String host, int port, String keyType, String previousFingerprint, String presentedFingerprint);
}
