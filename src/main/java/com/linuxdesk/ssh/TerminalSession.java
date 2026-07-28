package com.linuxdesk.ssh;

import org.apache.sshd.client.channel.ChannelShell;

import java.io.InputStream;
import java.io.OutputStream;

/** Wraps an interactive SSH PTY shell channel so the UI layer doesn't need the sshd client types. */
public class TerminalSession implements AutoCloseable {

    private final ChannelShell channel;

    TerminalSession(ChannelShell channel) {
        this.channel = channel;
    }

    public InputStream getOutput() {
        return channel.getInvertedOut();
    }

    public OutputStream getInput() {
        return channel.getInvertedIn();
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() {
        channel.close(false);
    }
}
