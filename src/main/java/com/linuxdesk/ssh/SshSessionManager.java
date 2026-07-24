package com.linuxdesk.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a single SSH + SFTP session for the connected VM.
 *
 * NOTE: host key verification is currently disabled (AcceptAllServerKeyVerifier).
 * That's a deliberate v1 shortcut to keep the login flow to one step; a real
 * known_hosts / trust-on-first-use prompt should replace it before this talks
 * to anything beyond a host the user already trusts.
 */
public class SshSessionManager implements AutoCloseable {

    private SshClient client;
    private ClientSession session;
    private SftpClient sftpClient;

    public void connect(String host, int port, String username, String privateKeyPath, String passphrase)
            throws IOException, GeneralSecurityException {
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();

        session = client.connect(username, host, port)
                .verify(15, TimeUnit.SECONDS)
                .getSession();

        FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(Path.of(privateKeyPath));
        if (passphrase != null && !passphrase.isEmpty()) {
            keyPairProvider.setPasswordFinder(FilePasswordProvider.of(passphrase));
        }
        for (KeyPair keyPair : keyPairProvider.loadKeys(session)) {
            session.addPublicKeyIdentity(keyPair);
        }

        session.auth().verify(15, TimeUnit.SECONDS);

        sftpClient = SftpClientFactory.instance().createSftpClient(session);
    }

    public String resolveHomeDirectory() throws IOException {
        return sftpClient.canonicalPath(".");
    }

    public List<RemoteEntry> listDirectory(String path) throws IOException {
        List<RemoteEntry> entries = new ArrayList<>();
        for (SftpClient.DirEntry dirEntry : sftpClient.readDir(path)) {
            String name = dirEntry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            boolean isDir = dirEntry.getAttributes().isDirectory();
            long size = dirEntry.getAttributes().getSize();
            String childPath = path.endsWith("/") ? path + name : path + "/" + name;
            entries.add(new RemoteEntry(name, childPath, isDir, size));
        }
        entries.sort((a, b) -> {
            if (a.directory() != b.directory()) {
                return a.directory() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return entries;
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    @Override
    public void close() {
        try {
            if (sftpClient != null) {
                sftpClient.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignored) {
        }
        if (client != null) {
            client.stop();
        }
    }
}
