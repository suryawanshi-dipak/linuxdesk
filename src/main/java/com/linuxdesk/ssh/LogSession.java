package com.linuxdesk.ssh;

import org.apache.sshd.client.channel.ChannelExec;

import java.io.InputStream;

/** Wraps a non-interactive exec channel streaming a live log tail (journalctl -f / tail -F). */
public class LogSession implements AutoCloseable {

    private final ChannelExec channel;

    LogSession(ChannelExec channel) {
        this.channel = channel;
    }

    public InputStream getOutput() {
        return channel.getInvertedOut();
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        channel.close(false);
    }
}
