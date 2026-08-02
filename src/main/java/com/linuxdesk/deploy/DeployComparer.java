package com.linuxdesk.deploy;

import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Walks a local directory and its remote deployment target, then classifies every file as
 * identical, modified, local-only, or remote-only. Directories aren't compared as entries
 * themselves — only the files within them, matching how the eventual file transfer works.
 * Files/directories matched by {@link IgnorePatterns} are pruned from both sides before comparing.
 */
public final class DeployComparer {

    private DeployComparer() {
    }

    public static List<DeployDiffEntry> compare(Path localRoot, SshSessionManager sessionManager, String remoteRoot,
                                                  IgnorePatterns ignorePatterns) throws IOException {
        return compare(localRoot, sessionManager, remoteRoot, ignorePatterns, false);
    }

    /**
     * @param verifyChecksums when two files have the same size, hash both sides (SHA-256, local
     *                        in-process / remote via `sha256sum`) instead of trusting size alone.
     *                        Slower (per SRS FR-DEP-003: "checksum optional for speed") but catches
     *                        same-size-different-content files the default size-only check would miss.
     */
    public static List<DeployDiffEntry> compare(Path localRoot, SshSessionManager sessionManager, String remoteRoot,
                                                  IgnorePatterns ignorePatterns, boolean verifyChecksums) throws IOException {
        Map<String, LocalFile> localFiles = scanLocal(localRoot, ignorePatterns);
        Map<String, RemoteEntry> remoteFiles = scanRemote(sessionManager, remoteRoot, ignorePatterns);

        Set<String> allPaths = new TreeSet<>();
        allPaths.addAll(localFiles.keySet());
        allPaths.addAll(remoteFiles.keySet());

        List<DeployDiffEntry> result = new ArrayList<>();
        for (String relativePath : allPaths) {
            LocalFile local = localFiles.get(relativePath);
            RemoteEntry remote = remoteFiles.get(relativePath);

            if (remote == null) {
                result.add(new DeployDiffEntry(relativePath, DeployDiffEntry.Status.LOCAL_ONLY,
                        local.size(), local.modifiedMillis(), -1, -1));
            } else if (local == null) {
                result.add(new DeployDiffEntry(relativePath, DeployDiffEntry.Status.REMOTE_ONLY,
                        -1, -1, remote.size(), remote.modifiedMillis()));
            } else {
                // Size only, not mtime: nothing yet preserves timestamps across an upload (that's
                // the future deploy step's job), so remote mtime is just "last deployed at" and
                // will almost always differ from the local edit time even for byte-identical
                // content. Using it here would make "Identical" nearly never trigger in practice.
                boolean sameSize = local.size() == remote.size();
                boolean identical = sameSize
                        && (!verifyChecksums || checksumsMatch(localRoot.resolve(relativePath), sessionManager, remote.path()));
                DeployDiffEntry.Status status = identical ? DeployDiffEntry.Status.IDENTICAL : DeployDiffEntry.Status.MODIFIED;
                result.add(new DeployDiffEntry(relativePath, status,
                        local.size(), local.modifiedMillis(), remote.size(), remote.modifiedMillis()));
            }
        }
        return result;
    }

    private static boolean checksumsMatch(Path localFile, SshSessionManager sessionManager, String remotePath) {
        try {
            String localHash = sha256(localFile);
            String remoteHash = sessionManager.sha256(remotePath);
            return localHash.equalsIgnoreCase(remoteHash);
        } catch (IOException e) {
            // Can't hash one side (permissions, transient error, etc.) — fall back to "not identical"
            // rather than silently reporting a false match.
            return false;
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, LocalFile> scanLocal(Path root, IgnorePatterns ignorePatterns) throws IOException {
        Map<String, LocalFile> files = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String relativePath = toRelativeUnixPath(root, dir);
                String name = dir.getFileName().toString();
                if (ignorePatterns.isDirectoryIgnored(relativePath, name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relativePath = toRelativeUnixPath(root, file);
                String name = file.getFileName().toString();
                if (ignorePatterns.isFileIgnored(relativePath, name)) {
                    return FileVisitResult.CONTINUE;
                }
                files.put(relativePath, new LocalFile(attrs.size(), attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static Map<String, RemoteEntry> scanRemote(SshSessionManager sessionManager, String remoteRoot,
                                                         IgnorePatterns ignorePatterns) throws IOException {
        Map<String, RemoteEntry> files = new LinkedHashMap<>();
        if (sessionManager.exists(remoteRoot)) {
            collectRemote(sessionManager, remoteRoot, remoteRoot, ignorePatterns, files);
        }
        return files;
    }

    private static void collectRemote(SshSessionManager sessionManager, String rootPath, String currentPath,
                                       IgnorePatterns ignorePatterns, Map<String, RemoteEntry> out) throws IOException {
        for (RemoteEntry entry : sessionManager.listDirectory(currentPath)) {
            String relativePath = relativize(rootPath, entry.path());
            if (entry.directory()) {
                if (ignorePatterns.isDirectoryIgnored(relativePath, entry.name())) {
                    continue;
                }
                collectRemote(sessionManager, rootPath, entry.path(), ignorePatterns, out);
            } else {
                if (ignorePatterns.isFileIgnored(relativePath, entry.name())) {
                    continue;
                }
                out.put(relativePath, entry);
            }
        }
    }

    private static String relativize(String root, String fullPath) {
        String normalizedRoot = root.endsWith("/") ? root : root + "/";
        return fullPath.startsWith(normalizedRoot) ? fullPath.substring(normalizedRoot.length()) : fullPath;
    }

    private static String toRelativeUnixPath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private record LocalFile(long size, long modifiedMillis) {
    }
}
