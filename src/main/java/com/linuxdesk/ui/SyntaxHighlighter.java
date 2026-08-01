package com.linuxdesk.ui;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based highlighter for the CodeArea editor: comments/strings/numbers are recognized
 * generically across languages, plus a small keyword set picked by file extension. Not a real
 * parser — good enough for readability, not for anything that needs to understand the syntax.
 */
final class SyntaxHighlighter {

    private static final String[] JAVA_KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "record", "return", "sealed", "short",
            "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "var", "void", "volatile", "while", "yield", "true", "false", "null"
    };

    private static final String[] PYTHON_KEYWORDS = {
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in",
            "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield"
    };

    private static final String[] JS_KEYWORDS = {
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "export", "extends", "finally", "for", "function", "if", "import", "in",
            "instanceof", "let", "new", "of", "return", "super", "switch", "this", "throw", "try", "typeof",
            "var", "void", "while", "with", "yield", "true", "false", "null", "undefined"
    };

    private static final String[] SHELL_KEYWORDS = {
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
            "function", "in", "select", "time", "export", "local", "return", "exit", "break", "continue",
            "echo", "read", "shift", "source", "alias", "unset", "declare", "readonly", "set", "eval", "exec"
    };

    private final Pattern pattern;

    SyntaxHighlighter(String fileName) {
        this.pattern = buildPattern(keywordsForFile(fileName));
    }

    StyleSpans<Collection<String>> highlight(String text) {
        Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String styleClass = matcher.group("KEYWORD") != null ? "code-keyword"
                    : matcher.group("STRING") != null ? "code-string"
                    : matcher.group("COMMENT") != null ? "code-comment"
                    : matcher.group("NUMBER") != null ? "code-number"
                    : null;
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    private static Pattern buildPattern(String[] keywords) {
        String keywordPattern = keywords.length > 0 ? "\\b(" + String.join("|", keywords) + ")\\b" : "\\b\\B";
        // STRING and COMMENT avoid repeating a capturing alternation group with `*`
        // (e.g. the old `([^"\\]|\\.)*`) — Java's regex engine matches that recursively,
        // one stack frame per character, and a long line or an unterminated quote/comment
        // blows the stack (StackOverflowError) instead of just failing to match. The
        // "unrolled loop" form below (plain char-class run, then optional escape, repeated)
        // and the scoped-dotall `.*?` are both flat/iterative instead.
        return Pattern.compile(
                "(?<KEYWORD>" + keywordPattern + ")"
                        + "|(?<STRING>\"[^\"\\\\\\n]*(?:\\\\.[^\"\\\\\\n]*)*\"|'[^'\\\\\\n]*(?:\\\\.[^'\\\\\\n]*)*')"
                        + "|(?<COMMENT>//[^\n]*|#[^\n]*|/\\*(?s:.*?)\\*/)"
                        + "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)");
    }

    private static String[] keywordsForFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return JAVA_KEYWORDS;
        }
        if (lower.endsWith(".py")) {
            return PYTHON_KEYWORDS;
        }
        if (lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".jsx") || lower.endsWith(".tsx")) {
            return JS_KEYWORDS;
        }
        if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.equals(".bashrc")
                || lower.equals(".profile") || lower.equals(".bash_profile")) {
            return SHELL_KEYWORDS;
        }
        return new String[0];
    }
}
