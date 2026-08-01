package com.linuxdesk.ui;

import com.linuxdesk.deploy.DeployComparer;
import com.linuxdesk.deploy.DeployDiffEntry;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * First slice of the Deployment workflow (SRS §5.4): local↔remote comparison only. No file
 * transfer, backup, or rollback yet — this just answers "what would change."
 */
public class DeployController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @FXML private TextField localPathField;
    @FXML private TextField remotePathField;
    @FXML private Button compareButton;
    @FXML private Label statusLabel;

    @FXML private TableView<DeployDiffEntry> diffTable;
    @FXML private TableColumn<DeployDiffEntry, String> statusColumn;
    @FXML private TableColumn<DeployDiffEntry, String> pathColumn;
    @FXML private TableColumn<DeployDiffEntry, String> localSizeColumn;
    @FXML private TableColumn<DeployDiffEntry, String> remoteSizeColumn;
    @FXML private TableColumn<DeployDiffEntry, String> localModifiedColumn;
    @FXML private TableColumn<DeployDiffEntry, String> remoteModifiedColumn;

    private SshSessionManager sessionManager;
    private Path localRoot;
    private final ObservableList<DeployDiffEntry> diffEntries = FXCollections.observableArrayList();

    public void init(SshSessionManager sessionManager, Stage stage, String initialRemotePath) {
        this.sessionManager = sessionManager;
        stage.setTitle("Deploy");
        remotePathField.setText(initialRemotePath == null ? "" : initialRemotePath);

        statusColumn.setCellValueFactory(data -> new SimpleStringProperty(statusText(data.getValue().status())));
        statusColumn.setCellFactory(col -> statusCell());
        pathColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().relativePath()));
        localSizeColumn.setCellValueFactory(data -> new SimpleStringProperty(sizeText(data.getValue().localSize())));
        remoteSizeColumn.setCellValueFactory(data -> new SimpleStringProperty(sizeText(data.getValue().remoteSize())));
        localModifiedColumn.setCellValueFactory(data ->
                new SimpleStringProperty(timeText(data.getValue().localModifiedMillis())));
        remoteModifiedColumn.setCellValueFactory(data ->
                new SimpleStringProperty(timeText(data.getValue().remoteModifiedMillis())));

        diffTable.setItems(diffEntries);
        compareButton.setDisable(true);
    }

    @FXML
    private void onBrowseLocal() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select local folder to deploy");
        File selected = chooser.showDialog(localPathField.getScene().getWindow());
        if (selected != null) {
            localRoot = selected.toPath();
            localPathField.setText(selected.getAbsolutePath());
            compareButton.setDisable(false);
        }
    }

    @FXML
    private void onCompare() {
        if (localRoot == null) {
            statusLabel.setText("Choose a local folder first.");
            return;
        }
        String remotePath = remotePathField.getText().trim();
        if (remotePath.isEmpty()) {
            statusLabel.setText("Enter a remote path first.");
            return;
        }

        compareButton.setDisable(true);
        statusLabel.setText("Comparing...");
        diffEntries.clear();

        Thread worker = new Thread(() -> {
            try {
                List<DeployDiffEntry> result = DeployComparer.compare(localRoot, sessionManager, remotePath);
                Platform.runLater(() -> {
                    diffEntries.setAll(result);
                    statusLabel.setText(summaryText(result));
                    compareButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Compare failed: " + e.getMessage());
                    compareButton.setDisable(false);
                });
            }
        }, "deploy-compare");
        worker.setDaemon(true);
        worker.start();
    }

    private static String summaryText(List<DeployDiffEntry> entries) {
        long identical = entries.stream().filter(e -> e.status() == DeployDiffEntry.Status.IDENTICAL).count();
        long modified = entries.stream().filter(e -> e.status() == DeployDiffEntry.Status.MODIFIED).count();
        long localOnly = entries.stream().filter(e -> e.status() == DeployDiffEntry.Status.LOCAL_ONLY).count();
        long remoteOnly = entries.stream().filter(e -> e.status() == DeployDiffEntry.Status.REMOTE_ONLY).count();
        return entries.size() + " files — " + identical + " identical, " + modified + " modified, "
                + localOnly + " new locally, " + remoteOnly + " remote only";
    }

    private static String statusText(DeployDiffEntry.Status status) {
        return switch (status) {
            case IDENTICAL -> "Identical";
            case MODIFIED -> "Modified";
            case LOCAL_ONLY -> "New (local)";
            case REMOTE_ONLY -> "Remote only";
        };
    }

    private static TableCell<DeployDiffEntry, String> statusCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("diff-status-identical", "diff-status-modified",
                        "diff-status-local-only", "diff-status-remote-only");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                setText(value);
                switch (value) {
                    case "Identical" -> getStyleClass().add("diff-status-identical");
                    case "Modified" -> getStyleClass().add("diff-status-modified");
                    case "New (local)" -> getStyleClass().add("diff-status-local-only");
                    case "Remote only" -> getStyleClass().add("diff-status-remote-only");
                    default -> { }
                }
            }
        };
    }

    private static String sizeText(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.1f GB", gb);
    }

    private static String timeText(long millis) {
        if (millis < 0) {
            return "—";
        }
        return TIME_FORMAT.format(Instant.ofEpochMilli(millis));
    }
}
