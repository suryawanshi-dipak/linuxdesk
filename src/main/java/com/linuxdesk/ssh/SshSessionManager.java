package com.linuxdesk.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.EnumSet;
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

    public String readFile(String path) throws IOException {
        try (InputStream in = sftpClient.read(path, EnumSet.of(SftpClient.OpenMode.Read))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void writeFile(String path, String content) throws IOException {
        try (OutputStream out = sftpClient.write(path,
                EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean exists(String path) {
        try {
            sftpClient.stat(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void rename(String oldPath, String newPath) throws IOException {
        sftpClient.rename(oldPath, newPath);
    }

    public void renameOverwrite(String oldPath, String newPath) throws IOException {
        sftpClient.rename(oldPath, newPath, SftpClient.CopyMode.Overwrite);
    }

    public void createDirectory(String path) throws IOException {
        sftpClient.mkdir(path);
    }

    public void delete(RemoteEntry entry) throws IOException {
        if (entry.directory()) {
            deleteDirectoryRecursive(entry.path());
        } else {
            sftpClient.remove(entry.path());
        }
    }

    private void deleteDirectoryRecursive(String path) throws IOException {
        for (SftpClient.DirEntry dirEntry : sftpClient.readDir(path)) {
            String name = dirEntry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String childPath = path.endsWith("/") ? path + name : path + "/" + name;
            if (dirEntry.getAttributes().isDirectory()) {
                deleteDirectoryRecursive(childPath);
            } else {
                sftpClient.remove(childPath);
            }
        }
        sftpClient.rmdir(path);
    }

    /** Copies a file or directory tree. SFTP has no server-side copy, so this streams the bytes through the client. */
    public void copy(RemoteEntry entry, String destPath) throws IOException {
        if (entry.directory()) {
            copyDirectory(entry.path(), destPath);
        } else {
            copyFile(entry.path(), destPath);
        }
    }

    private void copyDirectory(String srcPath, String destPath) throws IOException {
        sftpClient.mkdir(destPath);
        for (SftpClient.DirEntry dirEntry : sftpClient.readDir(srcPath)) {
            String name = dirEntry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String childSrc = srcPath.endsWith("/") ? srcPath + name : srcPath + "/" + name;
            String childDest = destPath.endsWith("/") ? destPath + name : destPath + "/" + name;
            if (dirEntry.getAttributes().isDirectory()) {
                copyDirectory(childSrc, childDest);
            } else {
                copyFile(childSrc, childDest);
            }
        }
    }

    private void copyFile(String srcPath, String destPath) throws IOException {
        try (InputStream in = sftpClient.read(srcPath, EnumSet.of(SftpClient.OpenMode.Read));
             OutputStream out = sftpClient.write(destPath,
                     EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate))) {
            in.transferTo(out);
        }
    }

    /** Uploads a local file or directory tree into the given remote path. */
    public void upload(File localFile, String remotePath) throws IOException {
        if (localFile.isDirectory()) {
            uploadDirectory(localFile, remotePath);
        } else {
            uploadFile(localFile, remotePath);
        }
    }

    private void uploadDirectory(File localDir, String remotePath) throws IOException {
        if (!exists(remotePath)) {
            sftpClient.mkdir(remotePath);
        }
        File[] children = localDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String childRemotePath = remotePath.endsWith("/") ? remotePath + child.getName() : remotePath + "/" + child.getName();
            if (child.isDirectory()) {
                uploadDirectory(child, childRemotePath);
            } else {
                uploadFile(child, childRemotePath);
            }
        }
    }

    private void uploadFile(File localFile, String remotePath) throws IOException {
        try (InputStream in = new FileInputStream(localFile);
             OutputStream out = sftpClient.write(remotePath,
                     EnumSet.of(SftpClient.OpenMode.Create, SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate))) {
            in.transferTo(out);
        }
    }

    /** Downloads a remote file or directory tree into the given local path. */
    public void download(RemoteEntry entry, File localTarget) throws IOException {
        if (entry.directory()) {
            downloadDirectory(entry.path(), localTarget);
        } else {
            downloadFile(entry.path(), localTarget);
        }
    }

    private void downloadDirectory(String remotePath, File localDir) throws IOException {
        if (!localDir.exists() && !localDir.mkdirs()) {
            throw new IOException("Failed to create local directory: " + localDir);
        }
        for (SftpClient.DirEntry dirEntry : sftpClient.readDir(remotePath)) {
            String name = dirEntry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            String childRemote = remotePath.endsWith("/") ? remotePath + name : remotePath + "/" + name;
            File childLocal = new File(localDir, name);
            if (dirEntry.getAttributes().isDirectory()) {
                downloadDirectory(childRemote, childLocal);
            } else {
                downloadFile(childRemote, childLocal);
            }
        }
    }

    private void downloadFile(String remotePath, File localFile) throws IOException {
        File parent = localFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create local directory: " + parent);
        }
        try (InputStream in = sftpClient.read(remotePath, EnumSet.of(SftpClient.OpenMode.Read));
             OutputStream out = new FileOutputStream(localFile)) {
            in.transferTo(out);
        }
    }

    /** Opens an interactive PTY shell channel for the terminal window. */
    public TerminalSession openTerminal() throws IOException {
        ChannelShell channel = session.createShellChannel();
        channel.setPtyType("xterm-256color");
        channel.setPtyColumns(120);
        channel.setPtyLines(40);
        channel.setEnv("TERM", "xterm-256color");
        channel.open().verify(15, TimeUnit.SECONDS);
        return new TerminalSession(channel);
    }

    /** Snapshot of remote processes via `ps`, sorted by CPU descending (server-side). */
    public List<RemoteProcess> listProcesses() throws IOException {
        CommandResult result = execRaw("ps -eo pid,user,%cpu,rss,stat,comm --no-headers --sort=-%cpu");
        List<RemoteProcess> processes = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 6);
            if (parts.length < 6) {
                continue;
            }
            try {
                int pid = Integer.parseInt(parts[0]);
                String user = parts[1];
                double cpu = Double.parseDouble(parts[2]);
                long rssKb = Long.parseLong(parts[3]);
                String stat = parts[4];
                String comm = parts[5];
                processes.add(new RemoteProcess(pid, user, cpu, rssKb, stat, comm));
            } catch (NumberFormatException ignored) {
                // skip a line ps didn't format as expected
            }
        }
        return processes;
    }

    /** Ends a remote process. Uses SIGKILL to match the "End Task" semantics of forcing termination. */
    public void killProcess(int pid) throws IOException {
        CommandResult result = execRaw("kill -9 " + pid);
        if (result.exitStatus() != null && result.exitStatus() != 0) {
            String message = result.output().trim();
            throw new IOException(message.isEmpty() ? "kill exited with status " + result.exitStatus() : message);
        }
    }

    private record CommandResult(String output, Integer exitStatus) {
    }

    private CommandResult execRaw(String command) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ChannelExec channel = session.createExecChannel(command)) {
            channel.setOut(out);
            channel.setErr(out);
            channel.open().verify(10, TimeUnit.SECONDS);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TimeUnit.SECONDS.toMillis(15));
            return new CommandResult(out.toString(StandardCharsets.UTF_8), channel.getExitStatus());
        }
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
