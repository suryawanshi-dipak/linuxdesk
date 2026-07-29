package com.linuxdesk.ui;

import com.linuxdesk.ssh.LogSession;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LogViewerController {

    private static final int TAIL_LINES = 300;

    @FXML private RadioButton journalRadio;
    @FXML private RadioButton fileRadio;
    @FXML private TextField pathField;
    @FXML private TextField searchField;
    @FXML private CheckBox autoScrollCheck;
    @FXML private Label statusLabel;
    @FXML private TextArea logArea;

    private SshSessionManager sessionManager;
    private LogSession logSession;
    private AnsiFilter ansiFilter = new AnsiFilter();
    private final List<String> allLines = new ArrayList<>();
    private String currentFilter = "";
    private volatile boolean stopRequested = false;

    public void init(SshSessionManager sessionManager, Stage stage) {
        this.sessionManager = sessionManager;
        stage.setTitle("Log Viewer");
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> stopTail());

        ToggleGroup sourceGroup = new ToggleGroup();
        journalRadio.setToggleGroup(sourceGroup);
        fileRadio.setToggleGroup(sourceGroup);
        pathField.disableProperty().bind(fileRadio.selectedProperty().not());

        searchField.textProperty().addListener((obs, old, text) -> {
            currentFilter = text == null ? "" : text.trim();
            rerenderFromModel();
        });

        startTail();
    }

    @FXML
    private void onStart() {
        startTail();
    }

    @FXML
    private void onStop() {
        stopTail();
        statusLabel.setText("Stopped.");
    }

    @FXML
    private void onClear() {
        allLines.clear();
        logArea.clear();
    }

    private void startTail() {
        stopTail();
        allLines.clear();
        logArea.clear();
        ansiFilter = new AnsiFilter();
        stopRequested = false;

        String command = buildCommand();
        statusLabel.setText("Starting...");

        Thread worker = new Thread(() -> {
            try {
                LogSession session = sessionManager.tailLog(command);
                logSession = session;
                Platform.runLater(() -> statusLabel.setText("Streaming..."));
                pumpOutput(session.getOutput());
            } catch (IOException e) {
                Platform.runLater(() -> statusLabel.setText("Failed to start: " + e.getMessage()));
            }
        }, "log-tail");
        worker.setDaemon(true);
        worker.start();
    }

    private String buildCommand() {
        if (fileRadio.isSelected()) {
            String path = pathField.getText().trim();
            if (path.isEmpty()) {
                path = "/var/log/syslog";
            }
            return "tail -F -n " + TAIL_LINES + " " + shellQuote(path);
        }
        return "journalctl -n " + TAIL_LINES + " -f --no-pager -o short-iso";
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private void stopTail() {
        stopRequested = true;
        if (logSession != null) {
            logSession.close();
            logSession = null;
        }
    }

    private void pumpOutput(InputStream out) {
        byte[] buffer = new byte[4096];
        StringBuilder pending = new StringBuilder();
        try {
            int read;
            while (!stopRequested && (read = out.read(buffer)) != -1) {
                String chunk = ansiFilter.filter(new String(buffer, 0, read, StandardCharsets.UTF_8));
                pending.append(chunk);
                List<String> lines = extractCompleteLines(pending);
                if (!lines.isEmpty()) {
                    Platform.runLater(() -> appendLines(lines));
                }
            }
        } catch (IOException ignored) {
            // channel closed
        }
    }

    private static List<String> extractCompleteLines(StringBuilder pending) {
        List<String> lines = new ArrayList<>();
        int newlineIndex;
        while ((newlineIndex = pending.indexOf("\n")) != -1) {
            lines.add(pending.substring(0, newlineIndex));
            pending.delete(0, newlineIndex + 1);
        }
        return lines;
    }

    private void appendLines(List<String> lines) {
        for (String line : lines) {
            allLines.add(line);
            if (matchesFilter(line, currentFilter)) {
                logArea.appendText(line + "\n");
            }
        }
        if (autoScrollCheck.isSelected()) {
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void rerenderFromModel() {
        StringBuilder sb = new StringBuilder();
        for (String line : allLines) {
            if (matchesFilter(line, currentFilter)) {
                sb.append(line).append('\n');
            }
        }
        logArea.setText(sb.toString());
        if (autoScrollCheck.isSelected()) {
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private static boolean matchesFilter(String line, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return line.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
