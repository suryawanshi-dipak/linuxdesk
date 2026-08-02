package com.linuxdesk.ssh;

/** Result of an arbitrary remote command: its combined stdout/stderr and exit status. */
public record CommandOutcome(String output, Integer exitStatus) {
    public boolean succeeded() {
        return exitStatus != null && exitStatus == 0;
    }
}
