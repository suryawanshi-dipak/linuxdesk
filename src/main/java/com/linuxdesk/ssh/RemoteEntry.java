package com.linuxdesk.ssh;

public record RemoteEntry(String name, String path, boolean directory, long size) {
}
