package com.linuxdesk.ui;

import com.linuxdesk.App;
import com.linuxdesk.model.ConnectionProfile;
import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class DesktopController {

    @FXML private Label hostLabel;
    @FXML private Label pathLabel;
    @FXML private Button backButton;
    @FXML private FlowPane iconGrid;
    @FXML private ScrollPane scrollPane;
    @FXML private Label statusLabel;

    private SshSessionManager sessionManager;
    private final Deque<String> history = new ArrayDeque<>();
    private String currentPath;

    public void init(SshSessionManager sessionManager, ConnectionProfile profile, String rootPath) {
        this.sessionManager = sessionManager;
        hostLabel.setText(profile.getUsername() + "@" + profile.getHost());
        backButton.setDisable(true);
        navigateTo(rootPath, false);
    }

    @FXML
    private void onBack() {
        if (!history.isEmpty()) {
            String previous = history.pop();
            navigateTo(previous, false);
            backButton.setDisable(history.isEmpty());
        }
    }

    @FXML
    private void onDisconnect() {
        sessionManager.close();
        try {
            App.loadScene("/com/linuxdesk/login.fxml", 760, 430);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to return to login screen", e);
        }
    }

    private void navigateTo(String path, boolean pushHistory) {
        if (pushHistory && currentPath != null) {
            history.push(currentPath);
            backButton.setDisable(false);
        }
        currentPath = path;
        pathLabel.setText(path);
        statusLabel.setText("Loading...");
        iconGrid.getChildren().clear();

        Thread worker = new Thread(() -> {
            try {
                List<RemoteEntry> entries = sessionManager.listDirectory(path);
                Platform.runLater(() -> renderEntries(entries));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to list directory: " + e.getMessage()));
            }
        }, "sftp-list");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderEntries(List<RemoteEntry> entries) {
        iconGrid.getChildren().clear();
        for (RemoteEntry entry : entries) {
            iconGrid.getChildren().add(createIconNode(entry));
        }
        statusLabel.setText(entries.size() + " item" + (entries.size() == 1 ? "" : "s"));
    }

    private VBox createIconNode(RemoteEntry entry) {
        VBox box = new VBox(6);
        box.setAlignment(Pos.TOP_CENTER);
        box.getStyleClass().add("desktop-icon");
        box.setPrefWidth(96);

        StackPane icon = entry.directory() ? IconFactory.createFolderIcon() : IconFactory.createFileIcon();

        Label label = new Label(entry.name());
        label.getStyleClass().add("desktop-icon-label");
        label.setWrapText(true);
        label.setAlignment(Pos.TOP_CENTER);
        label.setMaxWidth(90);

        box.getChildren().addAll(icon, label);

        box.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && entry.directory()) {
                navigateTo(entry.path(), true);
            }
        });

        return box;
    }
}
