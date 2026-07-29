package com.linuxdesk.ssh;

/** Raw jiffie counters from /proc/stat's aggregate "cpu" line; CPU% is a delta between two samples. */
public record CpuTimes(long user, long nice, long system, long idle, long iowait, long irq, long softirq, long steal) {

    public long total() {
        return user + nice + system + idle + iowait + irq + softirq + steal;
    }

    public long idleAll() {
        return idle + iowait;
    }
