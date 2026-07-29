package com.linuxdesk.ssh;

public record DiskUsage(String filesystem, String size, String used, String avail, String usePercent, String mountedOn) {
}
