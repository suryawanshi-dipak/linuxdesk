package com.linuxdesk.deploy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A small subset of `.gitignore` syntax (SRS FR-DEP-012): blank lines and `#` comments are
 * skipped, a trailing `/` restricts a pattern to directories, `*`/`?` are glob wildcards, and a
 * pattern containing `/` is anchored to the deploy root while one without matches by name at any
 * depth. No negation (`!`), no `**`, no character classes — a deliberately simple subset, not a
 * full gitignore parser.
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
            boolean directoryOnly = line.endsWith("/");
            if (directoryOnly) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.isEmpty()) {
                continue;
            }
            boolean anchored = line.contains("/");
            String body = line.startsWith("/") ? line.substring(1) : line;
            compiled.add(new CompiledPattern(globToRegex(body), directoryOnly, anchored));
        }
        return new IgnorePatterns(compiled);
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.', '(', ')', '+', '^', '$', '|', '[', ']', '{', '}', '\\' -> sb.append('\\').append(c);
                default -> sb.append(c);
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

    private boolean matches(String relativePath, String name, boolean isDirectory) {
        for (CompiledPattern p : patterns) {
            if (p.directoryOnly() && !isDirectory) {
                continue;
            }
            String candidate = p.anchored() ? relativePath : name;
            if (p.regex().matcher(candidate).matches()) {
                return true;
            }
        }
        return false;
    }

    private record CompiledPattern(Pattern regex, boolean directoryOnly, boolean anchored) {
    }
}
