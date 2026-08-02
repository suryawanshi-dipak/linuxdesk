package com.linuxdesk.ui;

import com.linuxdesk.audit.AuditRecorder;
import com.linuxdesk.deploy.DeployBackupRecord;
import com.linuxdesk.deploy.DeployBackupStore;
import com.linuxdesk.deploy.DeployComparer;
import com.linuxdesk.deploy.DeployDiffEntry;
import com.linuxdesk.deploy.IgnorePatterns;
import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Deployment workflow (SRS §5.4): local↔remote comparison, selective sync with a
 * deployment plan shown before executing, automatic backup of overwritten files, one-click
 * rollback to the immediately-previous deployment, dry-run mode, and typed confirmation for
 * Production-tagged targets. Still ahead: pre/post-deploy hooks, health checks, named
 * deployment profiles/history — see the Roadmap wiki page.
 */
public class DeployController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter BACKUP_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    @FXML private TextField localPathField;
    @FXML private TextField remotePathField;
    @FXML private Label productionBadge;
    @FXML private Button compareButton;
    @FXML private Button selectAllButton;
    @FXML private Button selectNoneButton;
    @FXML private CheckBox dryRunCheck;
    @FXML private Button rollbackButton;
    @FXML private Button deployButton;
    @FXML private TextArea ignorePatternsArea;
    @FXML private Label statusLabel;

    @FXML private TableView<DiffRow> diffTable;
    @FXML private TableColumn<DiffRow, String> statusColumn;
    @FXML private TableColumn<DiffRow, String> pathColumn;
    @FXML private TableColumn<DiffRow, String> localSizeColumn;
    @FXML private TableColumn<DiffRow, String> remoteSizeColumn;
    @FXML private TableColumn<DiffRow, String> localModifiedColumn;
    @FXML private TableColumn<DiffRow, String> remoteModifiedColumn;

    private SshSessionManager sessionManager;
    private String host;
    private boolean production;
    private AuditRecorder auditRecorder;
    private final DeployBackupStore backupStore = new DeployBackupStore();
    private Path localRoot;
    private String remoteRoot;
    private boolean backupAvailable;
    private final ObservableList<DiffRow> diffRows = FXCollections.observableArrayList();

    public void init(SshSessionManager sessionManager, Stage stage, String initialRemotePath,
                      String host, boolean production, AuditRecorder auditRecorder) {
        this.sessionManager = sessionManager;
        this.host = host;
        this.production = production;
        this.auditRecorder = auditRecorder;
        stage.setTitle("Deploy");
        remotePathField.setText(initialRemotePath == null ? "" : initialRemotePath);
        ignorePatternsArea.setText(String.join("\n", IgnorePatterns.DEFAULT_PATTERNS));
        productionBadge.setVisible(production);
        productionBadge.setManaged(production);

        TableColumn<DiffRow, Boolean> selectColumn = new TableColumn<>("");
        selectColumn.setCellValueFactory(data -> data.getValue().selected);
        selectColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectColumn));
        selectColumn.setEditable(true);
        selectColumn.setPrefWidth(32);
        selectColumn.setSortable(false);
        diffTable.getColumns().add(0, selectColumn);
        diffTable.setEditable(true);

        statusColumn.setCellValueFactory(data -> new SimpleStringProperty(statusText(data.getValue().entry.status())));
        statusColumn.setCellFactory(col -> statusCell());
        pathColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().entry.relativePath()));
        localSizeColumn.setCellValueFactory(data -> new SimpleStringProperty(sizeText(data.getValue().entry.localSize())));
        remoteSizeColumn.setCellValueFactory(data -> new SimpleStringProperty(sizeText(data.getValue().entry.remoteSize())));
        localModifiedColumn.setCellValueFactory(data ->
                new SimpleStringProperty(timeText(data.getValue().entry.localModifiedMillis())));
        remoteModifiedColumn.setCellValueFactory(data ->
                new SimpleStringProperty(timeText(data.getValue().entry.remoteModifiedMillis())));

        diffTable.setItems(diffRows);
        refreshRollbackAvailability();
        setBusy(false);
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
        remoteRoot = remotePath;
        IgnorePatterns ignorePatterns = IgnorePatterns.fromText(ignorePatternsArea.getText());

        setBusy(true);
        statusLabel.setText("Comparing...");
        diffRows.clear();

        Thread worker = new Thread(() -> {
            try {
                List<DeployDiffEntry> result = DeployComparer.compare(localRoot, sessionManager, remotePath, ignorePatterns);
                Platform.runLater(() -> {
                    diffRows.setAll(result.stream().map(DiffRow::new).toList());
                    statusLabel.setText(summaryText(result));
                    refreshRollbackAvailability();
                    setBusy(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Compare failed: " + e.getMessage());
                    setBusy(false);
                });
            }
        }, "deploy-compare");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onImportGitignore() {
        if (localRoot == null) {
            statusLabel.setText("Choose a local folder first.");
            return;
        }
        List<String> lines = IgnorePatterns.readGitignoreLines(localRoot);
        if (lines.isEmpty()) {
            statusLabel.setText("No .gitignore found in " + localRoot);
            return;
        }
        String existing = ignorePatternsArea.getText();
        String addition = "# from .gitignore\n" + String.join("\n", lines);
        ignorePatternsArea.setText(existing.isBlank() ? addition : existing + "\n" + addition);
        statusLabel.setText("Imported " + lines.size() + " line(s) from .gitignore.");
    }

    @FXML
    private void onSelectAll() {
        for (DiffRow row : diffRows) {
            if (row.entry.status() != DeployDiffEntry.Status.IDENTICAL) {
                row.selected.set(true);
            }
        }
    }

    @FXML
    private void onSelectNone() {
        for (DiffRow row : diffRows) {
            row.selected.set(false);
        }
    }

    @FXML
    private void onDeploySelected() {
        List<DiffRow> toUpload = new ArrayList<>();
        List<DiffRow> toDelete = new ArrayList<>();
        for (DiffRow row : diffRows) {
            if (!row.selected.get()) {
                continue;
            }
            switch (row.entry.status()) {
                case MODIFIED, LOCAL_ONLY -> toUpload.add(row);
                case REMOTE_ONLY -> toDelete.add(row);
                case IDENTICAL -> { }
            }
        }
        if (toUpload.isEmpty() && toDelete.isEmpty()) {
            statusLabel.setText("Nothing selected to deploy.");
            return;
        }
        List<DiffRow> toBackup = toUpload.stream()
                .filter(row -> row.entry.status() == DeployDiffEntry.Status.MODIFIED)
                .toList();
        String plan = buildPlanText(toUpload, toDelete, toBackup);

        if (dryRunCheck.isSelected()) {
            Alert info = new Alert(Alert.AlertType.INFORMATION, plan, ButtonType.OK);
            ThemeManager.apply(info.getDialogPane());
            info.setHeaderText("Dry run — nothing will be executed.");
            info.showAndWait();
            return;
        }

        IgnorePatterns ignorePatterns = IgnorePatterns.fromText(ignorePatternsArea.getText());
        if (production) {
            confirmProductionThenDeploy(plan, toUpload, toDelete, toBackup, ignorePatterns);
        } else {
            confirmThenDeploy(plan, toUpload, toDelete, toBackup, ignorePatterns);
        }
    }

    private void confirmThenDeploy(String plan, List<DiffRow> toUpload, List<DiffRow> toDelete,
                                    List<DiffRow> toBackup, IgnorePatterns ignorePatterns) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, plan, ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isPresent() && choice.get() == ButtonType.YES) {
            startDeploy(toUpload, toDelete, toBackup, ignorePatterns);
        }
    }

    /** Production targets require typing the host, not just a button click — same friction level as recursive delete. */
    private void confirmProductionThenDeploy(String plan, List<DiffRow> toUpload, List<DiffRow> toDelete,
                                              List<DiffRow> toBackup, IgnorePatterns ignorePatterns) {
        TextInputDialog dialog = new TextInputDialog();
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("Confirm deploy to production host");
        dialog.setContentText(plan + "\n\nThis is a PRODUCTION host (" + host + ").\nType the host to confirm:");
        dialog.showAndWait().ifPresent(typed -> {
            if (typed.trim().equals(host)) {
                startDeploy(toUpload, toDelete, toBackup, ignorePatterns);
            } else {
                statusLabel.setText("Confirmation text didn't match — deploy cancelled.");
            }
        });
    }

    private void startDeploy(List<DiffRow> toUpload, List<DiffRow> toDelete, List<DiffRow> toBackup,
                              IgnorePatterns ignorePatterns) {
        setBusy(true);
        statusLabel.setText("Deploying...");

        Thread worker = new Thread(() -> runDeploy(toUpload, toDelete, toBackup, ignorePatterns), "deploy-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private void runDeploy(List<DiffRow> toUpload, List<DiffRow> toDelete, List<DiffRow> toBackup,
                            IgnorePatterns ignorePatterns) {
        if (!toBackup.isEmpty()) {
            Instant now = Instant.now();
            String timestamp = BACKUP_TIMESTAMP_FORMAT.format(now);
            List<String> backupPaths = toBackup.stream().map(row -> row.entry.relativePath()).toList();
            try {
                String backupPath = sessionManager.backupFilesForDeploy(remoteRoot, backupPaths, timestamp);
                backupStore.record(host, remoteRoot, backupPath, now.toEpochMilli());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Backup failed, deploy aborted: " + e.getMessage());
                    auditRecorder.log("Deploy", "failure", "backup step failed: " + e.getMessage());
                    setBusy(false);
                });
                return;
            }
        }

        int uploaded = 0;
        int deleted = 0;
        List<String> errors = new ArrayList<>();

        for (DiffRow row : toUpload) {
            String relativePath = row.entry.relativePath();
            File localFile = localRoot.resolve(relativePath).toFile();
            String remotePath = remoteRoot.endsWith("/") ? remoteRoot + relativePath : remoteRoot + "/" + relativePath;
            try {
                sessionManager.uploadFileEnsuringParents(localFile, remotePath);
                uploaded++;
            } catch (Exception e) {
                errors.add(relativePath + ": " + e.getMessage());
            }
            int uploadedSoFar = uploaded;
            Platform.runLater(() -> statusLabel.setText("Uploading... " + uploadedSoFar + "/" + toUpload.size()));
        }

        for (DiffRow row : toDelete) {
            String relativePath = row.entry.relativePath();
            String remotePath = remoteRoot.endsWith("/") ? remoteRoot + relativePath : remoteRoot + "/" + relativePath;
            try {
                sessionManager.delete(new RemoteEntry(fileName(relativePath), remotePath, false,
                        row.entry.remoteSize(), row.entry.remoteModifiedMillis()));
                deleted++;
            } catch (Exception e) {
                errors.add(relativePath + ": " + e.getMessage());
            }
        }

        int finalUploaded = uploaded;
        int finalDeleted = deleted;
        String detail = finalUploaded + " uploaded, " + finalDeleted + " deleted"
                + (toBackup.isEmpty() ? "" : ", " + toBackup.size() + " backed up");
        auditRecorder.log("Deploy", errors.isEmpty() ? "success" : "partial failure",
                errors.isEmpty() ? detail : detail + " — " + errors.size() + " failed: " + errors.get(0));

        Platform.runLater(() -> {
            String summary = detail;
            if (!errors.isEmpty()) {
                summary += ", " + errors.size() + " failed (" + errors.get(0) + ")";
            }
            statusLabel.setText(summary);
            refreshRollbackAvailability();
            setBusy(false);
        });

        // Refresh the comparison so the table reflects the new remote state.
        try {
            List<DeployDiffEntry> result = DeployComparer.compare(localRoot, sessionManager, remoteRoot, ignorePatterns);
            Platform.runLater(() -> diffRows.setAll(result.stream().map(DiffRow::new).toList()));
        } catch (Exception ignored) {
            // Deploy already reported its own outcome above; a failed refresh isn't itself an error.
        }
    }

    @FXML
    private void onRollback() {
        Optional<DeployBackupRecord> record = backupStore.find(host, remoteRoot == null ? "" : remoteRoot);
        if (record.isEmpty()) {
            statusLabel.setText("No backup available to roll back to.");
            return;
        }
        DeployBackupRecord backup = record.get();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Restore the backup from " + timeText(backup.timestamp())
                        + "?\n\nThis overwrites the current files on " + remoteRoot + " with the backed-up versions.\n"
                        + "Only files that were modified by that deploy are restored — new uploads and deletions"
                        + " from that deploy are not undone.",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.YES) {
            return;
        }

        setBusy(true);
        statusLabel.setText("Rolling back...");
        IgnorePatterns ignorePatterns = IgnorePatterns.fromText(ignorePatternsArea.getText());

        Thread worker = new Thread(() -> {
            try {
                sessionManager.restoreDeployBackup(remoteRoot, backup.backupPath());
                auditRecorder.log("Rollback", "success", "restored " + backup.backupPath());
                Platform.runLater(() -> statusLabel.setText("Rolled back to " + timeText(backup.timestamp()) + "."));
            } catch (Exception e) {
                auditRecorder.log("Rollback", "failure", e.getMessage());
                Platform.runLater(() -> statusLabel.setText("Rollback failed: " + e.getMessage()));
            }
            Platform.runLater(() -> setBusy(false));

            try {
                List<DeployDiffEntry> result = DeployComparer.compare(localRoot, sessionManager, remoteRoot, ignorePatterns);
                Platform.runLater(() -> diffRows.setAll(result.stream().map(DiffRow::new).toList()));
            } catch (Exception ignored) {
                // Rollback already reported its own outcome above.
            }
        }, "deploy-rollback");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshRollbackAvailability() {
        backupAvailable = remoteRoot != null && backupStore.find(host, remoteRoot).isPresent();
    }

    private String buildPlanText(List<DiffRow> toUpload, List<DiffRow> toDelete, List<DiffRow> toBackup) {
        List<String> steps = new ArrayList<>();
        if (!toBackup.isEmpty()) {
            steps.add("Back up " + toBackup.size() + " file(s) that will be overwritten");
        }
        if (!toUpload.isEmpty()) {
            long totalBytes = toUpload.stream().mapToLong(row -> Math.max(row.entry.localSize(), 0)).sum();
            steps.add("Upload " + toUpload.size() + " file(s) (" + sizeText(totalBytes) + ")");
        }
        if (!toDelete.isEmpty()) {
            steps.add("Delete " + toDelete.size() + " remote-only file(s)");
        }
        StringBuilder sb = new StringBuilder("Deployment plan\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        }
        sb.append("\nTarget: ").append(remoteRoot);
        return sb.toString();
    }

    private static String fileName(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash >= 0 ? relativePath.substring(lastSlash + 1) : relativePath;
    }

    private void setBusy(boolean busy) {
        compareButton.setDisable(busy || localRoot == null);
        deployButton.setDisable(busy || diffRows.isEmpty());
        selectAllButton.setDisable(busy || diffRows.isEmpty());
        selectNoneButton.setDisable(busy || diffRows.isEmpty());
        rollbackButton.setDisable(busy || !backupAvailable);
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

    private static TableCell<DiffRow, String> statusCell() {
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

    /** Wraps an immutable DeployDiffEntry with the observable checkbox state the table needs. */
    private static final class DiffRow {
        final DeployDiffEntry entry;
        final SimpleBooleanProperty selected;

        DiffRow(DeployDiffEntry entry) {
            this.entry = entry;
            // Modified/new-local files default to selected (safe, additive); remote-only
            // deletions default to unselected — deleting is opt-in, matching the SRS mockup.
            this.selected = new SimpleBooleanProperty(
                    entry.status() == DeployDiffEntry.Status.MODIFIED
                            || entry.status() == DeployDiffEntry.Status.LOCAL_ONLY);
        }
    }
}
