package com.linuxdesk.ui;

import com.linuxdesk.ssh.SshSessionManager;
import com.linuxdesk.ssh.TerminalSession;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class TerminalController {

    @FXML private TextArea outputArea;
    @FXML private TextField commandField;

    private TerminalSession terminalSession;
    private final AnsiFilter ansiFilter = new AnsiFilter();

    public void init(SshSessionManager sessionManager, String title, Stage stage) {
        init(sessionManager, title, null, stage);
    }

    public void init(SshSessionManager sessionManager, String title, String initialDirectory, Stage stage) {
        stage.setTitle(title + " — Terminal");
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> closeTerminal());

        commandField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                sendCommand();
            }
        });

        Thread worker = new Thread(() -> {
            try {
                terminalSession = sessionManager.openTerminal();
                if (initialDirectory != null && !initialDirectory.isEmpty()) {
                    sendRaw("cd " + shellQuote(initialDirectory) + "\n");
                }
                pumpOutput(terminalSession.getOutput());
            } catch (IOException e) {
                Platform.runLater(() -> {
                    outputArea.appendText("Failed to open terminal: " + e.getMessage() + "\n");
                    commandField.setDisable(true);
                });
            }
        }, "ssh-terminal");
        worker.setDaemon(true);
        worker.start();
    }

    private static String shellQuote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    private void pumpOutput(InputStream out) {
        byte[] buffer = new byte[4096];
        try {
            int read;
            while ((read = out.read(buffer)) != -1) {
                String chunk = ansiFilter.filter(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (!chunk.isEmpty()) {
                    Platform.runLater(() -> outputArea.appendText(chunk));
                }
            }
        } catch (IOException ignored) {
            // channel closed
        }
    }

    private void sendCommand() {
        String command = commandField.getText();
        commandField.clear();
        try {
            sendRaw(command + "\n");
        } catch (IOException e) {
            outputArea.appendText("\n[send failed: " + e.getMessage() + "]\n");
        }
    }

    private void sendRaw(String text) throws IOException {
        if (terminalSession == null || !terminalSession.isOpen()) {
            return;
        }
        OutputStream in = terminalSession.getInput();
        in.write(text.getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    private void closeTerminal() {
        if (terminalSession != null) {
            terminalSession.close();
        }
    }
}
