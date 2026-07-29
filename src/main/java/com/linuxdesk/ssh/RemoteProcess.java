package com.linuxdesk.ssh;

public record RemoteProcess(int pid, String user, double cpuPercent, long memoryKb, String status, String command) {
}
