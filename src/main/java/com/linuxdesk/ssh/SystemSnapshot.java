package com.linuxdesk.ssh;

import java.util.List;

public record SystemSnapshot(CpuTimes cpuTimes, MemoryInfo memory, List<DiskUsage> disks) {
}
