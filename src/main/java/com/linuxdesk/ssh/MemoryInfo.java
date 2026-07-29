package com.linuxdesk.ssh;

public record MemoryInfo(long totalBytes, long usedBytes, long availableBytes, long swapTotalBytes, long swapUsedBytes) {
}
