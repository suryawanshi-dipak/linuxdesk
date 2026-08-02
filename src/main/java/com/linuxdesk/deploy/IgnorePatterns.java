package com.linuxdesk.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A subset of `.gitignore` syntax (SRS FR-DEP-012): blank lines and `#` comments are skipped, a
 * trailing `/` restricts a pattern to directories, a leading `!` re-includes a path an earlier
 * pattern excluded (last matching pattern wins, same as git), `**` matches across any number of
 * path segments while a single `*`/`?` stays within one segment, and a pattern containing `/`
 * (other than a trailing one) is anchored to the deploy root while one without matches by name at
 * any depth. No character classes (`[abc]`) — still a deliberately simple subset, not a full
 * gitignore parser.
 */
public final class IgnorePatterns {

    /** SRS FR-DEP-013's specified default ignore set. */
    public static final List<String> DEFAULT_PATTERNS = List.of(
            ".git", "node_modules", "__pycache__", ".env", "*.log", ".DS_Store",
            "target/", "build/", "dist/", ".idea", ".vscode");

    private final List<CompiledPattern> patterns;

    private IgnorePatterns(List<CompiledPattern> patterns) {
        this.patterns = patterns;
    }

    public static IgnorePatterns defaults() {
        return fromLines(DEFAULT_PATTERNS);
    }

    public static IgnorePatterns fromText(String text) {
        return fromLines(text == null ? List.of() : Arrays.asList(text.split("\\R")));
    }

    /** Reads `.gitignore` from the local deploy root, if present; empty (no patterns) otherwise. */
    public static List<String> readGitignoreLines(Path localRoot) {
        Path gitignore = localRoot.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) {
            return List.of();
        }
        try {
            return Files.readAllLines(gitignore);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static IgnorePatterns fromLines(List<String> lines) {
        List<CompiledPattern> compiled = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            boolean negated = line.startsWith("!");
            if (negated) {
                line = line.substring(1);
            }
            boolean directoryOnly = line.endsWith("/");
            if (directoryOnly) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                continue;
            }
            // A trailing-slash-stripped pattern with no other '/' isn't "anchored" in the
            // gitignore sense (e.g. "target/" still matches at any depth, not just the root).
            boolean anchored = line.contains("/");
            String body = line.startsWith("/") ? line.substring(1) : line;
            compiled.add(new CompiledPattern(globToRegex(body), directoryOnly, anchored, negated));
        }
        return new IgnorePatterns(compiled);
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                sb.append(".*"); // crosses path-segment boundaries
                i += 2;
            } else if (c == '*') {
                sb.append("[^/]*"); // stays within one path segment
                i++;
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if (".()+^$|[]{}\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return Pattern.compile("^" + sb + "$");
    }

    public boolean isDirectoryIgnored(String relativePath, String name) {
        return matches(relativePath, name, true);
    }

    public boolean isFileIgnored(String relativePath, String name) {
        return matches(relativePath, name, false);
    }

    /** Evaluates every applicable pattern in order; the last one that matches decides (negation included). */
    private boolean matches(String relativePath, String name, boolean isDirectory) {
        boolean ignored = false;
        for (CompiledPattern p : patterns) {
            if (p.directoryOnly() && !isDirectory) {
                continue;
            }
            String candidate = p.anchored() ? relativePath : name;
            if (p.regex().matcher(candidate).matches()) {
                ignored = !p.negated();
            }
        }
        return ignored;
    }

    private record CompiledPattern(Pattern regex, boolean directoryOnly, boolean anchored, boolean negated) {
    }
}
