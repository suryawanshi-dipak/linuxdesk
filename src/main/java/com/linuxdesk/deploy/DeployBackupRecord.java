package com.linuxdesk.deploy;

/** The most recent deploy backup for one (host, remoteRoot) target, for one-click rollback. */
public record DeployBackupRecord(String host, String remoteRoot, String backupPath, long timestamp) {
}
