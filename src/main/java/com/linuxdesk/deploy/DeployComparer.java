package com.linuxdesk.deploy;

import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
 */
public final class DeployComparer {

    private static final Set<String> IGNORED_DIR_NAMES =
            Set.of(".git", "node_modules", "__pycache__", "target", "build", "dist", ".idea", ".vscode");
    private static final Set<String> IGNORED_FILE_NAMES = Set.of(".env", ".DS_Store");

    private DeployComparer() {
    }

    public static List<DeployDiffEntry> compare(Path localRoot, SshSessionManager sessionManager, String remoteRoot)
            throws IOException {
        Map<String, LocalFile> localFiles = scanLocal(localRoot);
        Map<String, RemoteEntry> remoteFiles = scanRemote(sessionManager, remoteRoot);

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
                DeployDiffEntry.Status status = (local.size() == remote.size())
                        ? DeployDiffEntry.Status.IDENTICAL : DeployDiffEntry.Status.MODIFIED;
                result.add(new DeployDiffEntry(relativePath, status,
                        local.size(), local.modifiedMillis(), remote.size(), remote.modifiedMillis()));
            }
        }
        return result;
    }

    private static Map<String, LocalFile> scanLocal(Path root) throws IOException {
        Map<String, LocalFile> files = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && IGNORED_DIR_NAMES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (IGNORED_FILE_NAMES.contains(name) || name.endsWith(".log")) {
                    return FileVisitResult.CONTINUE;
                }
                String relativePath = toRelativeUnixPath(root, file);
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

    private static Map<String, RemoteEntry> scanRemote(SshSessionManager sessionManager, String remoteRoot)
            throws IOException {
        Map<String, RemoteEntry> files = new LinkedHashMap<>();
        String normalizedRoot = remoteRoot.endsWith("/") ? remoteRoot : remoteRoot + "/";
        for (RemoteEntry entry : sessionManager.listTreeRecursive(remoteRoot)) {
            String relativePath = entry.path().startsWith(normalizedRoot)
                    ? entry.path().substring(normalizedRoot.length())
                    : entry.path();
            files.put(relativePath, entry);
        }
        return files;
    }

    private static String toRelativeUnixPath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private record LocalFile(long size, long modifiedMillis) {
    }
}
