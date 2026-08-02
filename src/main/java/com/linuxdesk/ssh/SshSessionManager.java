package com.linuxdesk.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Security;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a single SSH + SFTP session for the connected VM.
 *
 * Host keys are verified against a per-user known_hosts store at
 * {@code ~/.linuxdesk/known_hosts} (trust-on-first-use, via {@link #buildServerKeyVerifier}).
 *
 * Supports two auth methods: a private key file (any format Apache MINA SSHD / BouncyCastle can
 * parse — OpenSSH, PEM/PKCS#1, PKCS#8, RSA/ECDSA/Ed25519/DSA, encrypted or not) or a plain
 * password. Which one is used is inferred from which credential the caller supplies.
 */
public class SshSessionManager implements AutoCloseable {

    private static final Path KNOWN_HOSTS_FILE =
            Path.of(System.getProperty("user.home"), ".linuxdesk", "known_hosts");

    static {
        // Apache MINA SSHD's own format/algorithm detection is unreliable across JDKs for some
        // key types; registering BouncyCastle explicitly gives it a provider it always trusts,
        // widening supported private-key formats beyond the JDK-native default.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private SshClient client;
    private ClientSession session;
    private SftpClient sftpClient;
    private volatile String hostKeyRejectionReason;

    /**
     * Connects and authenticates. If {@code password} is non-blank, password authentication is
     * used and {@code privateKeyPath}/{@code passphrase} are ignored; otherwise the private key
     * at {@code privateKeyPath} is used (decrypted with {@code passphrase} if it's encrypted).
     */
    public void connect(String host, int port, String username, String privateKeyPath, String passphrase,
                         String password, HostKeyPrompt hostKeyPrompt)
            throws IOException, GeneralSecurityException {
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(buildServerKeyVerifier(host, port, hostKeyPrompt));
        // Without this, MINA SSHD mirrors the `ssh` CLI's default IdentityFile behavior and
        // silently tries the user's ~/.ssh/id_* files in addition to whatever credentials we
        // explicitly supply below — meaning a wrong password could "succeed" via an unrelated
        // key. We want strictly only the credential the user entered in the UI.
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.start();

        hostKeyRejectionReason = null;
        try {
            session = client.connect(username, host, port)
                    .verify(15, TimeUnit.SECONDS)
                    .getSession();
        } catch (IOException e) {
            if (hostKeyRejectionReason != null) {
                throw new IOException(hostKeyRejectionReason, e);
            }
            throw e;
        }

        if (password != null && !password.isEmpty()) {
            session.addPasswordIdentity(password);
        } else {
            loadPrivateKeyIdentity(session, privateKeyPath, passphrase);
        }

        session.auth().verify(15, TimeUnit.SECONDS);

        sftpClient = SftpClientFactory.instance().createSftpClient(session);
    }

    private void loadPrivateKeyIdentity(ClientSession session, String privateKeyPath, String passphrase)
            throws IOException, GeneralSecurityException {
        if (privateKeyPath == null || privateKeyPath.isBlank()) {
            throw new IOException("No private key file selected.");
        }
        Path keyPath = Path.of(privateKeyPath);
        if (!Files.isRegularFile(keyPath)) {
            throw new IOException("Private key file not found: " + privateKeyPath);
        }
        if (looksLikePublicKey(keyPath)) {
            throw new IOException("\"" + keyPath.getFileName() + "\" looks like a public key, not a private key. "
                    + "Select the matching private key file instead (usually the same name without \".pub\").");
        }

        FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyPath);
        if (passphrase != null && !passphrase.isEmpty()) {
            keyPairProvider.setPasswordFinder(FilePasswordProvider.of(passphrase));
        }
        int loaded = 0;
        for (KeyPair keyPair : keyPairProvider.loadKeys(session)) {
            session.addPublicKeyIdentity(keyPair);
            loaded++;
        }
        if (loaded == 0) {
            throw new IOException("Could not read a usable key from \"" + keyPath.getFileName() + "\". "
                    + "Check it's a supported private key format (OpenSSH, PEM/PKCS#1, or PKCS#8) and, "
                    + "if it's encrypted, that the passphrase is correct.");
        }
    }

    /** Public key files start with a type marker like "ssh-ed25519 AAAA..." on their first line. */
    private static boolean looksLikePublicKey(Path keyPath) throws IOException {
        String firstLine;
        try (var lines = Files.lines(keyPath, StandardCharsets.UTF_8)) {
            firstLine = lines.filter(l -> !l.isBlank()).findFirst().orElse("").trim();
        }
        return firstLine.startsWith("ssh-rsa ") || firstLine.startsWith("ssh-ed25519 ")
                || firstLine.startsWith("ssh-dss ") || firstLine.startsWith("ecdsa-sha2-")
                || firstLine.startsWith("sk-ssh-ed25519@") || firstLine.startsWith("sk-ecdsa-sha2-");
    }

    private ServerKeyVerifier buildServerKeyVerifier(String host, int port, HostKeyPrompt hostKeyPrompt) throws IOException {
        Files.createDirectories(KNOWN_HOSTS_FILE.getParent());

        ServerKeyVerifier tofuDelegate = (clientSession, remoteAddress, serverKey) -> {
            String keyType = KeyUtils.getKeyType(serverKey);
            String sha256 = KeyUtils.getFingerPrint(serverKey);
            String md5 = KeyUtils.getFingerPrint(BuiltinDigests.md5, serverKey);
            boolean accepted = hostKeyPrompt.confirmUnknownHost(host, port, keyType, sha256, md5);
            if (!accepted) {
                hostKeyRejectionReason = "Connection cancelled: host key for " + host + " was not trusted.";
            }
            return accepted;
        };

        DefaultKnownHostsServerKeyVerifier verifier =
                new DefaultKnownHostsServerKeyVerifier(tofuDelegate, false, KNOWN_HOSTS_FILE);
        verifier.setModifiedServerKeyAcceptor((clientSession, remoteAddress, entry, expected, actual) -> {
            String keyType = KeyUtils.getKeyType(actual);
            String previousFingerprint = KeyUtils.getFingerPrint(expected);
            String presentedFingerprint = KeyUtils.getFingerPrint(actual);
            boolean accepted = hostKeyPrompt.confirmChangedHost(host, port, keyType, previousFingerprint, presentedFingerprint);
            if (!accepted) {
                hostKeyRejectionReason = "Connection blocked: the host key for " + host
                        + " changed and was not accepted.";
            }
            return accepted;
        });
        return verifier;
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
            SftpClient.Attributes attrs = dirEntry.getAttributes();
            boolean isDir = attrs.isDirectory();
            String childPath = path.endsWith("/") ? path + name : path + "/" + name;
            entries.add(new RemoteEntry(name, childPath, isDir, attrs.getSize(), attrs.getModifyTime().toMillis()));
        }
        entries.sort((a, b) -> {
            if (a.directory() != b.directory()) {
                return a.directory() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return entries;
    }

    /** Recursively lists every file (not directories) under {@code rootPath}, for local↔remote comparison. */
    public List<RemoteEntry> listTreeRecursive(String rootPath) throws IOException {
        List<RemoteEntry> files = new ArrayList<>();
        if (exists(rootPath)) {
            collectTreeFiles(rootPath, files);
        }
        return files;
    }

    private void collectTreeFiles(String path, List<RemoteEntry> out) throws IOException {
        for (SftpClient.DirEntry dirEntry : sftpClient.readDir(path)) {
            String name = dirEntry.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            SftpClient.Attributes attrs = dirEntry.getAttributes();
            String childPath = path.endsWith("/") ? path + name : path + "/" + name;
            if (attrs.isDirectory()) {
                collectTreeFiles(childPath, out);
            } else {
                out.add(new RemoteEntry(name, childPath, false, attrs.getSize(), attrs.getModifyTime().toMillis()));
            }
        }
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

    /** Resolves a bare remote path (e.g. from the Recent Files list) back into a RemoteEntry. */
    public RemoteEntry statEntry(String path) throws IOException {
        SftpClient.Attributes attrs = sftpClient.stat(path);
        int lastSlash = path.lastIndexOf('/');
        String name = lastSlash >= 0 && lastSlash < path.length() - 1 ? path.substring(lastSlash + 1) : path;
        return new RemoteEntry(name, path, attrs.isDirectory(), attrs.getSize(), attrs.getModifyTime().toMillis());
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

    /**
     * Uploads a single local file to an exact remote path, creating any missing remote parent
     * directories first (`mkdir -p` equivalent). Used by the deploy sync step, where target
     * subdirectories may not exist yet on a first-time deploy.
     */
    public void uploadFileEnsuringParents(File localFile, String remotePath) throws IOException {
        int lastSlash = remotePath.lastIndexOf('/');
        if (lastSlash > 0) {
            ensureRemoteDirectories(remotePath.substring(0, lastSlash));
        }
        uploadFile(localFile, remotePath);
    }

    /** Creates {@code path} and any missing parent directories remotely, tolerating ones that already exist. */
    public void ensureRemoteDirectories(String path) throws IOException {
        if (path == null || path.isBlank() || path.equals("/") || exists(path)) {
            return;
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            ensureRemoteDirectories(path.substring(0, lastSlash));
        }
        try {
            sftpClient.mkdir(path);
        } catch (IOException e) {
            if (!exists(path)) {
                throw e;
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

    /** Opens a non-interactive exec channel streaming a live-tailing command (journalctl -f / tail -F). */
    public LogSession tailLog(String command) throws IOException {
        ChannelExec channel = session.createExecChannel(command);
        channel.open().verify(15, TimeUnit.SECONDS);
        return new LogSession(channel);
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

    /** Snapshot of systemd services, merging runtime state (list-units) with boot-enablement (list-unit-files). */
    public List<RemoteService> listServices() throws IOException {
        CommandResult unitsResult = execRaw("systemctl list-units --type=service --all --plain --no-legend --no-pager --full");
        CommandResult filesResult = execRaw("systemctl list-unit-files --type=service --plain --no-legend --no-pager");

        Map<String, String> enabledStates = new HashMap<>();
        for (String line : filesResult.output().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 3);
            if (parts.length >= 2) {
                enabledStates.put(parts[0], parts[1]);
            }
        }

        List<RemoteService> services = new ArrayList<>();
        for (String line : unitsResult.output().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 5);
            if (parts.length < 4) {
                continue;
            }
            String unit = parts[0];
            String load = parts[1];
            String active = parts[2];
            String sub = parts[3];
            String description = parts.length == 5 ? parts[4] : "";
            String enabled = enabledStates.getOrDefault(unit, "-");
            services.add(new RemoteService(unit, load, active, sub, enabled, description));
        }
        return services;
    }

    /** Runs a systemctl control action (start/stop/restart/enable/disable) via passwordless sudo. */
    public void controlService(String unit, String action) throws IOException {
        CommandResult result = execRaw("sudo -n systemctl " + action + " " + unit);
        if (result.exitStatus() != null && result.exitStatus() != 0) {
            String message = result.output().trim();
            throw new IOException(message.isEmpty() ? action + " exited with status " + result.exitStatus() : message);
        }
    }

    /** Current mode (octal, e.g. "0755") and owner/group of a remote path. */
    public RemotePermissions getPermissions(String path) throws IOException {
        CommandResult result = execRaw("stat -c '%a %U %G' " + shellQuote(path));
        String[] parts = result.output().trim().split("\\s+");
        if (parts.length < 3) {
            throw new IOException("Unexpected stat output: " + result.output().trim());
        }
        return new RemotePermissions(parts[0], parts[1], parts[2]);
    }

    /** Changes the mode of a path (and, if recursive, everything under it) to the given octal mode. */
    public void setPermissions(String path, String octalMode, boolean recursive) throws IOException {
        CommandResult result = execRaw("chmod " + (recursive ? "-R " : "") + octalMode + " " + shellQuote(path));
        if (result.exitStatus() != null && result.exitStatus() != 0) {
            String message = result.output().trim();
            throw new IOException(message.isEmpty() ? "chmod exited with status " + result.exitStatus() : message);
        }
    }

    /** Changes owner/group of a path (and, if recursive, everything under it) via passwordless sudo. */
    public void setOwnership(String path, String owner, String group, boolean recursive) throws IOException {
        String ownerGroup = group.isBlank() ? owner : owner + ":" + group;
        CommandResult result = execRaw("sudo -n chown " + (recursive ? "-R " : "") + shellQuote(ownerGroup) + " " + shellQuote(path));
        if (result.exitStatus() != null && result.exitStatus() != 0) {
            String message = result.output().trim();
            throw new IOException(message.isEmpty() ? "chown exited with status " + result.exitStatus() : message);
        }
    }

    /** Counts files/directories under a path, for previewing the blast radius of a recursive change. */
    public int countTree(String path) throws IOException {
        CommandResult result = execRaw("find " + shellQuote(path) + " | wc -l");
        try {
            return Integer.parseInt(result.output().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * One batched sample of CPU jiffies, memory, and disk usage for the monitor dashboard.
     * CPU is raw cumulative counters — the caller diffs two consecutive samples to get a percentage.
     */
    public SystemSnapshot sampleSystem() throws IOException {
        CommandResult result = execRaw("cat /proc/stat | head -1; echo '---SPLIT---'; free -b; "
                + "echo '---SPLIT---'; df -h --output=source,size,used,avail,pcent,target -x tmpfs -x devtmpfs -x squashfs 2>/dev/null");
        String[] sections = result.output().split("---SPLIT---");
        CpuTimes cpuTimes = parseCpuTimes(sections.length > 0 ? sections[0] : "");
        MemoryInfo memory = parseMemory(sections.length > 1 ? sections[1] : "");
        List<DiskUsage> disks = parseDisks(sections.length > 2 ? sections[2] : "");
        return new SystemSnapshot(cpuTimes, memory, disks);
    }

    private static CpuTimes parseCpuTimes(String section) {
        String trimmed = section.trim();
        String line = trimmed.isEmpty() ? "" : trimmed.split("\n")[0];
        String[] parts = line.trim().split("\\s+");
        long user = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        long nice = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
        long system = parts.length > 3 ? Long.parseLong(parts[3]) : 0;
        long idle = parts.length > 4 ? Long.parseLong(parts[4]) : 0;
        long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
        long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
        long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
        long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0;
        return new CpuTimes(user, nice, system, idle, iowait, irq, softirq, steal);
    }

    private static MemoryInfo parseMemory(String section) {
        long total = 0;
        long used = 0;
        long available = 0;
        long swapTotal = 0;
        long swapUsed = 0;
        for (String line : section.trim().split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Mem:")) {
                String[] parts = trimmed.split("\\s+");
                total = Long.parseLong(parts[1]);
                used = Long.parseLong(parts[2]);
                available = parts.length > 6 ? Long.parseLong(parts[6]) : Long.parseLong(parts[3]);
            } else if (trimmed.startsWith("Swap:")) {
                String[] parts = trimmed.split("\\s+");
                swapTotal = Long.parseLong(parts[1]);
                swapUsed = Long.parseLong(parts[2]);
            }
        }
        return new MemoryInfo(total, used, available, swapTotal, swapUsed);
    }

    private static List<DiskUsage> parseDisks(String section) {
        List<DiskUsage> disks = new ArrayList<>();
        String[] lines = section.trim().split("\n");
        for (int i = 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 6);
            if (parts.length < 6) {
                continue;
            }
            disks.add(new DiskUsage(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
        }
        return disks;
    }

    /**
     * Compresses {@code entryName} (a file or directory inside {@code parentDir}) into a new archive
     * named {@code archiveName}, created alongside it. Runs the server's native zip/tar rather than
     * streaming bytes through the SFTP client, since the server already has the source on disk.
     */
    public void compress(String parentDir, String entryName, String archiveName, ArchiveFormat format) throws IOException {
        String command = format == ArchiveFormat.ZIP
                ? "zip -r " + shellQuote(archiveName) + " " + shellQuote(entryName)
                : "tar -czf " + shellQuote(archiveName) + " " + shellQuote(entryName);
        CommandResult result = execRaw("cd " + shellQuote(parentDir) + " && " + command);
        if (result.exitStatus() != null && result.exitStatus() != 0) {
            String message = result.output().trim();
            throw new IOException(message.isEmpty() ? "compress exited with status " + result.exitStatus() : message);
        }
    }

    /**
     * Extracts the archive at {@code archivePath} into a fresh {@code destDir} (created if needed).
     * Extracting into a dedicated subdirectory rather than the current folder directly contains any
     * zip-slip / path-traversal entries within that subtree instead of scattering them elsewhere.
     */
    public void extractArchive(String archivePath, String destDir) throws IOException {
        String command = buildExtractCommand(archivePath);
        CommandResult result = execRaw("mkdir -p " + shellQuote(destDir) + " && cd " + shellQuote(destDir) + " && " + command);
        if (result.exitStatus() != null && result.exitStatus() != 0) {
            String message = result.output().trim();
            throw new IOException(message.isEmpty() ? "extract exited with status " + result.exitStatus() : message);
        }
    }

    private static String buildExtractCommand(String archivePath) throws IOException {
        String lower = archivePath.toLowerCase(Locale.ROOT);
        String quoted = shellQuote(archivePath);
        if (lower.endsWith(".zip")) {
            return "unzip -o " + quoted;
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return "tar -xzf " + quoted;
        }
        if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")) {
            return "tar -xjf " + quoted;
        }
        if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
            return "tar -xJf " + quoted;
        }
        if (lower.endsWith(".tar")) {
            return "tar -xf " + quoted;
        }
        throw new IOException("Unsupported archive type: " + archivePath);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
