package com.linuxdesk.ui;

import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

public class EditorController {

    @FXML private Label pathLabel;
    @FXML private Label statusLabel;
    @FXML private TextArea contentArea;
    @FXML private Button saveButton;

    private SshSessionManager sessionManager;
    private String path;
    private Stage stage;
    private String savedContent;

    public void init(SshSessionManager sessionManager, RemoteEntry entry, String content, Stage stage) {
        this.sessionManager = sessionManager;
        this.path = entry.path();
        this.stage = stage;
        this.savedContent = content;
        pathLabel.setText(entry.path());
        contentArea.setText(content);

        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (contentArea.getText().equals(savedContent)) {
                return;
            }
            event.consume();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "You have unsaved changes to " + path + ". Discard them?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(button -> {
                if (button == ButtonType.YES) {
                    stage.close();
                }
            });
        });
    }

    @FXML
    private void onSave() {
        String content = contentArea.getText();
        saveButton.setDisable(true);
        statusLabel.setText("Saving...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.writeFile(path, content);
                Platform.runLater(() -> {
                    savedContent = content;
                    statusLabel.setText("Saved.");
                    saveButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Save failed: " + e.getMessage());
                    saveButton.setDisable(false);
                });
            }
        }, "sftp-write");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onClose() {
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }
}
