package com.linuxdesk.ui;

import com.linuxdesk.audit.AuditRecorder;
import com.linuxdesk.deploy.DeployBackupRecord;
import com.linuxdesk.deploy.DeployBackupStore;
import com.linuxdesk.deploy.DeployComparer;
import com.linuxdesk.deploy.DeployDiffEntry;
import com.linuxdesk.deploy.HealthChecker;
import com.linuxdesk.deploy.IgnorePatterns;
import com.linuxdesk.ssh.CommandOutcome;
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
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ChoiceDialog;
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
 * The Deployment workflow (SRS §5.4): local↔remote comparison (size-only by default, opt-in
 * checksum verification), selective sync with a deployment plan shown before executing,
 * pre/post-deploy hooks, automatic backup of overwritten files with retention (keeps the last
 * {@link DeployBackupStore#MAX_RETAINED}), rollback to any retained backup, post-deploy health
 * checks with optional auto-rollback on failure, dry-run mode, and typed confirmation for
 * Production-tagged targets. Still ahead: named deployment profiles, full deployment history
 * with compare/repeat, zero-downtime symlink swap — see the Roadmap wiki page.
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
    @FXML private TextArea ignorePatternsArea;
    @FXML private TextField preHookField;
    @FXML private TextField postHookField;
    @FXML private ChoiceBox<HealthChecker.Type> healthCheckTypeChoice;
    @FXML private TextField healthCheckTargetField;
    @FXML private TextField healthCheckExpectedField;
    @FXML private TextField healthCheckRetriesField;
    @FXML private TextField healthCheckIntervalField;
    @FXML private CheckBox autoRollbackCheck;
    @FXML private CheckBox verifyChecksumsCheck;
    @FXML private Button selectAllButton;
    @FXML private Button selectNoneButton;
    @FXML private CheckBox dryRunCheck;
    @FXML private Button rollbackButton;
    @FXML private Button deployButton;
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

        healthCheckTypeChoice.getItems().setAll(HealthChecker.Type.values());
        healthCheckTypeChoice.setValue(HealthChecker.Type.NONE);

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
        boolean verifyChecksums = verifyChecksumsCheck.isSelected();

        setBusy(true);
        statusLabel.setText(verifyChecksums ? "Comparing (verifying checksums, this is slower)..." : "Comparing...");
        diffRows.clear();

        Thread worker = new Thread(() -> {
            try {
                List<DeployDiffEntry> result =
                        DeployComparer.compare(localRoot, sessionManager, remotePath, ignorePatterns, verifyChecksums);
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

        DeployRequest request = new DeployRequest(
                toUpload, toDelete, toBackup,
                IgnorePatterns.fromText(ignorePatternsArea.getText()),
                preHookField.getText().trim(),
                postHookField.getText().trim(),
                healthCheckTypeChoice.getValue(),
                healthCheckTargetField.getText().trim(),
                healthCheckExpectedField.getText().trim(),
                parseIntOr(healthCheckRetriesField.getText(), 3),
                parseIntOr(healthCheckIntervalField.getText(), 3),
                autoRollbackCheck.isSelected());

        String plan = buildPlanText(request);

        if (dryRunCheck.isSelected()) {
            Alert info = new Alert(Alert.AlertType.INFORMATION, plan, ButtonType.OK);
            ThemeManager.apply(info.getDialogPane());
            info.setHeaderText("Dry run — nothing will be executed.");
            info.showAndWait();
            return;
        }

        if (production) {
            confirmProductionThenDeploy(plan, request);
        } else {
            confirmThenDeploy(plan, request);
        }
    }

    private void confirmThenDeploy(String plan, DeployRequest request) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, plan, ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isPresent() && choice.get() == ButtonType.YES) {
            startDeploy(request);
        }
    }

    /** Production targets require typing the host, not just a button click — same friction level as recursive delete. */
    private void confirmProductionThenDeploy(String plan, DeployRequest request) {
        TextInputDialog dialog = new TextInputDialog();
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("Confirm deploy to production host");
        dialog.setContentText(plan + "\n\nThis is a PRODUCTION host (" + host + ").\nType the host to confirm:");
        dialog.showAndWait().ifPresent(typed -> {
            if (typed.trim().equals(host)) {
                startDeploy(request);
            } else {
                statusLabel.setText("Confirmation text didn't match — deploy cancelled.");
            }
        });
    }

    private void startDeploy(DeployRequest request) {
        setBusy(true);
        statusLabel.setText("Deploying...");

        Thread worker = new Thread(() -> runDeploy(request), "deploy-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private void runDeploy(DeployRequest req) {
        if (!req.preHook().isBlank()) {
            try {
                CommandOutcome outcome = sessionManager.runCommand(req.preHook());
                if (!outcome.succeeded()) {
                    abortDeploy("Pre-deploy hook failed (exit " + outcome.exitStatus() + "): " + firstLine(outcome.output()));
                    return;
                }
            } catch (Exception e) {
                abortDeploy("Pre-deploy hook failed: " + e.getMessage());
                return;
            }
        }

        String backupPath = null;
        if (!req.toBackup().isEmpty()) {
            Instant now = Instant.now();
            String timestamp = BACKUP_TIMESTAMP_FORMAT.format(now);
            List<String> backupPaths = req.toBackup().stream().map(row -> row.entry.relativePath()).toList();
            try {
                backupPath = sessionManager.backupFilesForDeploy(remoteRoot, backupPaths, timestamp);
                List<DeployBackupRecord> dropped = backupStore.record(host, remoteRoot, backupPath, now.toEpochMilli());
                pruneDroppedBackups(dropped);
            } catch (Exception e) {
                abortDeploy("Backup failed, deploy aborted: " + e.getMessage());
                return;
            }
        }
        String finalBackupPath = backupPath;

        int uploaded = 0;
        int deleted = 0;
        List<String> errors = new ArrayList<>();

        for (DiffRow row : req.toUpload()) {
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
            Platform.runLater(() -> statusLabel.setText("Uploading... " + uploadedSoFar + "/" + req.toUpload().size()));
        }

        for (DiffRow row : req.toDelete()) {
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

        if (errors.isEmpty() && !req.postHook().isBlank()) {
            try {
                CommandOutcome outcome = sessionManager.runCommand(req.postHook());
                if (!outcome.succeeded()) {
                    errors.add("post-deploy hook exit " + outcome.exitStatus() + ": " + firstLine(outcome.output()));
                }
            } catch (Exception e) {
                errors.add("post-deploy hook: " + e.getMessage());
            }
        }

        HealthChecker.Result healthResult = null;
        if (errors.isEmpty() && req.healthCheckType() != HealthChecker.Type.NONE) {
            Platform.runLater(() -> statusLabel.setText("Running health check..."));
            healthResult = HealthChecker.runWithRetry(req.healthCheckType(), req.healthCheckTarget(),
                    req.healthCheckExpected(), sessionManager, req.retries(), req.intervalSeconds());
            if (!healthResult.healthy()) {
                if (req.autoRollback() && finalBackupPath != null) {
                    try {
                        sessionManager.restoreDeployBackup(remoteRoot, finalBackupPath);
                        errors.add("health check failed (" + healthResult.message() + ") — automatically rolled back");
                        auditRecorder.log("Rollback", "success", "auto-rollback after failed health check: " + healthResult.message());
                    } catch (Exception e) {
                        errors.add("health check failed AND auto-rollback failed: " + e.getMessage());
                        auditRecorder.log("Rollback", "failure", "auto-rollback after failed health check: " + e.getMessage());
                    }
                } else {
                    errors.add("health check failed: " + healthResult.message());
                }
            }
        }

        int finalUploaded = uploaded;
        int finalDeleted = deleted;
        String detail = finalUploaded + " uploaded, " + finalDeleted + " deleted"
                + (req.toBackup().isEmpty() ? "" : ", " + req.toBackup().size() + " backed up");
        auditRecorder.log("Deploy", errors.isEmpty() ? "success" : "partial failure",
                errors.isEmpty() ? detail : detail + " — " + errors.get(0));

        Platform.runLater(() -> {
            String summary = detail;
            if (!errors.isEmpty()) {
                summary += ", " + errors.size() + " issue(s) (" + errors.get(0) + ")";
            }
            statusLabel.setText(summary);
            refreshRollbackAvailability();
            setBusy(false);
        });

        try {
            List<DeployDiffEntry> result = DeployComparer.compare(localRoot, sessionManager, remoteRoot, req.ignorePatterns());
            Platform.runLater(() -> diffRows.setAll(result.stream().map(DiffRow::new).toList()));
        } catch (Exception ignored) {
            // Deploy already reported its own outcome above; a failed refresh isn't itself an error.
        }
    }

    private void abortDeploy(String message) {
        auditRecorder.log("Deploy", "failure", message);
        Platform.runLater(() -> {
            statusLabel.setText(message);
            setBusy(false);
        });
    }

    private void pruneDroppedBackups(List<DeployBackupRecord> dropped) {
        for (DeployBackupRecord old : dropped) {
            try {
                String fullPath = remoteRoot.endsWith("/") ? remoteRoot + old.backupPath() : remoteRoot + "/" + old.backupPath();
                sessionManager.delete(new RemoteEntry(fileName(old.backupPath()), fullPath, false, 0, 0));
            } catch (Exception ignored) {
                // Best-effort cleanup; a stray old backup file isn't worth failing the deploy over.
            }
        }
    }

    @FXML
    private void onRollback() {
        List<DeployBackupRecord> backups = backupStore.list(host, remoteRoot == null ? "" : remoteRoot);
        if (backups.isEmpty()) {
            statusLabel.setText("No backup available to roll back to.");
            return;
        }
        List<BackupChoice> choices = backups.stream()
                .map(r -> new BackupChoice(r, timeText(r.timestamp()) + "  (" + r.backupPath() + ")"))
                .toList();
        ChoiceDialog<BackupChoice> dialog = new ChoiceDialog<>(choices.get(0), choices);
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("Roll back deployment");
        dialog.setContentText("Restore which backup for " + remoteRoot + "?\n"
                + "Only files that were modified by that deploy are restored — new uploads and deletions"
                + " from that deploy are not undone.");
        dialog.showAndWait().ifPresent(this::confirmAndRollback);
    }

    private void confirmAndRollback(BackupChoice choice) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Restore the backup from " + timeText(choice.record().timestamp())
                        + "?\n\nThis overwrites the current files on " + remoteRoot + " with the backed-up versions.",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.YES) {
            return;
        }

        setBusy(true);
        statusLabel.setText("Rolling back...");
        IgnorePatterns ignorePatterns = IgnorePatterns.fromText(ignorePatternsArea.getText());

        Thread worker = new Thread(() -> {
            DeployBackupRecord backup = choice.record();
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
        backupAvailable = remoteRoot != null && backupStore.latest(host, remoteRoot).isPresent();
    }

    private String buildPlanText(DeployRequest req) {
        List<String> steps = new ArrayList<>();
        if (!req.preHook().isBlank()) {
            steps.add("Run pre-deploy hook: " + req.preHook());
        }
        if (!req.toBackup().isEmpty()) {
            steps.add("Back up " + req.toBackup().size() + " file(s) that will be overwritten");
        }
        if (!req.toUpload().isEmpty()) {
            long totalBytes = req.toUpload().stream().mapToLong(row -> Math.max(row.entry.localSize(), 0)).sum();
            steps.add("Upload " + req.toUpload().size() + " file(s) (" + sizeText(totalBytes) + ")");
        }
        if (!req.toDelete().isEmpty()) {
            steps.add("Delete " + req.toDelete().size() + " remote-only file(s)");
        }
        if (!req.postHook().isBlank()) {
            steps.add("Run post-deploy hook: " + req.postHook());
        }
        if (req.healthCheckType() != HealthChecker.Type.NONE) {
            steps.add("Health check (" + req.healthCheckType() + "): " + req.healthCheckTarget()
                    + " — retry " + req.retries() + "x, " + req.intervalSeconds() + "s apart"
                    + (req.autoRollback() ? ", auto-rollback on failure" : ""));
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

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        int newline = trimmed.indexOf('\n');
        return newline >= 0 ? trimmed.substring(0, newline) : trimmed;
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
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

    /** Bundles one deploy execution's parameters so they don't have to thread through every method individually. */
    private record DeployRequest(List<DiffRow> toUpload, List<DiffRow> toDelete, List<DiffRow> toBackup,
                                  IgnorePatterns ignorePatterns, String preHook, String postHook,
                                  HealthChecker.Type healthCheckType, String healthCheckTarget, String healthCheckExpected,
                                  int retries, int intervalSeconds, boolean autoRollback) {
    }

    /** Gives a DeployBackupRecord a human-readable label for the rollback picker dialog. */
    private record BackupChoice(DeployBackupRecord record, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
