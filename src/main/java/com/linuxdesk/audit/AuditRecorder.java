package com.linuxdesk.audit;

/** Records one audit entry against whatever host/user the caller is currently connected as. */
@FunctionalInterface
public interface AuditRecorder {
    void log(String action, String outcome, String detail);
}
