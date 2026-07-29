package com.linuxdesk.ui;

import com.linuxdesk.App;
import com.linuxdesk.audit.AuditLogStore;
import com.linuxdesk.audit.AuditRecorder;
import com.linuxdesk.model.ConnectionProfile;
import com.linuxdesk.profile.RecentPathsStore;
import com.linuxdesk.ssh.ArchiveFormat;
import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class DesktopController {

    private static final long MAX_EDITABLE_SIZE = 2 * 1024 * 1024;

    @FXML private Label hostLabel;
    @FXML private Label productionBadge;
    @FXML private Label pathLabel;
    @FXML private Button backButton;
    @FXML private FlowPane iconGrid;
    @FXML private ScrollPane scrollPane;
    @FXML private Label statusLabel;
    @FXML private Button startButton;
    @FXML private TextField searchField;

    private enum SortMode { NAME, SIZE }

    private SshSessionManager sessionManager;
    private boolean production;
    private String hostKey;
    private String auditHost;
    private String auditUser;
    private final RecentPathsStore recentPathsStore = new RecentPathsStore();
    private final AuditLogStore auditLogStore = new AuditLogStore();
    private final AuditRecorder auditRecorder = this::logAudit;
    private final Deque<String> history = new ArrayDeque<>();
    private String currentPath;
    private RemoteEntry clipboardEntry;
    private List<RemoteEntry> currentEntries = List.of();
    private SortMode sortMode = SortMode.NAME;
    private Popup startMenuPopup;
    private Popup searchResultsPopup;
    private List<SearchHit> lastMatches = List.of();

    private enum HitKind { COMMAND, FOLDER, FILE }

    private record SearchHit(String label, HitKind kind, Runnable action) {
    }

    @FXML
    private void initialize() {
        scrollPane.setOnContextMenuRequested(event -> {
            createBackgroundContextMenu().show(scrollPane, event.getScreenX(), event.getScreenY());
        });

        startButton.setOnAction(e -> toggleStartMenu());

        searchField.textProperty().addListener((obs, old, text) -> {
            if (text == null || text.isBlank()) {
                lastMatches = List.of();
                if (searchField.isFocused()) {
                    showRecentItemsDropdown();
                } else {
                    hideSearchResults();
                }
            } else {
                updateSearchResults(text);
            }
        });
        searchField.setOnAction(e -> openTopMatch());
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                searchField.clear();
                hideSearchResults();
            }
        });
        searchField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                if (searchField.getText().isBlank()) {
                    showRecentItemsDropdown();
                } else {
                    updateSearchResults(searchField.getText());
                }
            }
        });

        iconGrid.setOnDragOver(event -> {
            if (event.getGestureSource() != iconGrid && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        iconGrid.setOnDragEntered(event -> {
            if (event.getDragboard().hasFiles()) {
                iconGrid.getStyleClass().add("desktop-surface-drag-over");
            }
        });
        iconGrid.setOnDragExited(event -> iconGrid.getStyleClass().remove("desktop-surface-drag-over"));
        iconGrid.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = db.hasFiles();
            if (success) {
                for (File file : db.getFiles()) {
                    startUpload(file, currentPath);
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void updateSearchResults(String text) {
        String query = text == null ? "" : text.trim();
        if (query.isEmpty()) {
            hideSearchResults();
            lastMatches = List.of();
            return;
        }
        String needle = query.toLowerCase(Locale.ROOT);

        List<SearchHit> hits = new ArrayList<>();
        addCommandHit(hits, needle, "Task Manager", this::onOpenTaskManager);
        addCommandHit(hits, needle, "Terminal", this::onOpenTerminal);
        addCommandHit(hits, needle, "Log Viewer", this::onOpenLogViewer);
        addCommandHit(hits, needle, "Monitor", this::onOpenMonitor);
        addCommandHit(hits, needle, "Audit Log", this::onOpenAuditLog);
        addCommandHit(hits, needle, "Disconnect", this::onDisconnect);

        currentEntries.stream()
                .filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(RemoteEntry::name, String.CASE_INSENSITIVE_ORDER))
                .limit(20)
                .forEach(entry -> hits.add(new SearchHit(entry.name(),
                        entry.directory() ? HitKind.FOLDER : HitKind.FILE,
                        () -> {
                            if (entry.directory()) {
                                navigateTo(entry.path(), true);
                            } else {
                                openFileEditor(entry);
                            }
                        })));

        lastMatches = hits;
        showSearchResults(hits);
    }

    private void addCommandHit(List<SearchHit> hits, String needle, String label, Runnable action) {
        if (label.toLowerCase(Locale.ROOT).contains(needle)) {
            hits.add(new SearchHit(label, HitKind.COMMAND, action));
        }
    }

    private void showSearchResults(List<SearchHit> hits) {
        renderResultsPopup(hits, "No matching items in this folder", null);
    }

    /** Shown when the search field is focused but empty: recently visited folders/files for this host. */
    private void showRecentItemsDropdown() {
        List<SearchHit> hits = new ArrayList<>();
        for (String dir : recentPathsStore.loadDirectories(hostKey)) {
            hits.add(new SearchHit(dir, HitKind.FOLDER, () -> navigateTo(dir, true)));
        }
        for (String file : recentPathsStore.loadFiles(hostKey)) {
            int lastSlash = file.lastIndexOf('/');
            String label = lastSlash >= 0 && lastSlash < file.length() - 1 ? file.substring(lastSlash + 1) : file;
            hits.add(new SearchHit(label, HitKind.FILE, () -> openRecentFile(file)));
        }
        lastMatches = hits;
        renderResultsPopup(hits, "No recent folders or files yet on this host",
                hits.isEmpty() ? null : () -> recentPathsStore.clear(hostKey));
    }

    private void openRecentFile(String path) {
        statusLabel.setText("Opening " + path + "...");
        Thread worker = new Thread(() -> {
            try {
                RemoteEntry entry = sessionManager.statEntry(path);
                Platform.runLater(() -> openFileEditor(entry));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to open " + path + ": " + e.getMessage()));
            }
        }, "sftp-stat");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderResultsPopup(List<SearchHit> hits, String emptyMessage, Runnable onClearAll) {
        hideSearchResults();

        VBox card = new VBox(2);
        card.getStyleClass().add("start-menu");
        card.setPrefWidth(Math.max(searchField.getWidth(), 280));
        ThemeManager.apply(card);

        if (hits.isEmpty()) {
            Label empty = new Label(emptyMessage);
            empty.getStyleClass().add("search-empty-label");
            card.getChildren().add(empty);
        } else {
            HitKind previousKind = null;
            for (SearchHit hit : hits) {
                if (previousKind == HitKind.COMMAND && hit.kind() != HitKind.COMMAND) {
                    card.getChildren().add(new Separator());
                }
                card.getChildren().add(createSearchResultRow(hit));
                previousKind = hit.kind();
            }
            if (onClearAll != null) {
                card.getChildren().add(new Separator());
                Label clearLabel = new Label("Clear recent items");
                HBox clearRow = new HBox(clearLabel);
                clearRow.setAlignment(Pos.CENTER_LEFT);
                clearRow.getStyleClass().add("search-result-row");
                clearRow.setOnMouseClicked(event -> {
                    onClearAll.run();
                    hideSearchResults();
                });
                card.getChildren().add(clearRow);
            }
        }

        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setAnchorLocation(PopupWindow.AnchorLocation.WINDOW_BOTTOM_LEFT);
        popup.getContent().add(card);

        Bounds bounds = searchField.localToScreen(searchField.getBoundsInLocal());
        popup.show(searchField, bounds.getMinX(), bounds.getMinY());
        searchResultsPopup = popup;
    }

    private void hideSearchResults() {
        if (searchResultsPopup != null) {
            searchResultsPopup.hide();
            searchResultsPopup = null;
        }
    }

    private HBox createSearchResultRow(SearchHit hit) {
        StackPane icon = switch (hit.kind()) {
            case COMMAND -> commandIcon();
            case FOLDER -> IconFactory.createSmallFolderIcon();
            case FILE -> IconFactory.createSmallFileIcon();
        };

        Label label = new Label(hit.label());
        label.getStyleClass().add("search-result-label");

        HBox row = new HBox(10, icon, label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("search-result-row");
        row.setOnMouseClicked(event -> openHit(hit));
        return row;
    }

    private StackPane commandIcon() {
        Label glyph = new Label("⌘");
        glyph.getStyleClass().add("search-command-icon");
        StackPane pane = new StackPane(glyph);
        pane.setPrefSize(20, 20);
        return pane;
    }

    private void openTopMatch() {
        if (!lastMatches.isEmpty()) {
            openHit(lastMatches.get(0));
        }
    }

    private void openHit(SearchHit hit) {
        hideSearchResults();
        searchField.clear();
        hit.action().run();
    }

    private void toggleStartMenu() {
        if (startMenuPopup != null && startMenuPopup.isShowing()) {
            startMenuPopup.hide();
            return;
        }
        startMenuPopup = buildStartMenu();
        Bounds bounds = startButton.localToScreen(startButton.getBoundsInLocal());
        startMenuPopup.show(startButton, bounds.getMinX(), bounds.getMinY());
    }

    private Popup buildStartMenu() {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setAnchorLocation(PopupWindow.AnchorLocation.WINDOW_BOTTOM_LEFT);

        VBox card = new VBox(2);
        card.getStyleClass().add("start-menu");
        ThemeManager.apply(card);

        Button taskManagerItem = startMenuItem("Task Manager");
        taskManagerItem.setOnAction(e -> {
            popup.hide();
            onOpenTaskManager();
        });

        Button terminalItem = startMenuItem("Terminal");
        terminalItem.setOnAction(e -> {
            popup.hide();
            onOpenTerminal();
        });

        Button logViewerItem = startMenuItem("Log Viewer");
        logViewerItem.setOnAction(e -> {
            popup.hide();
            onOpenLogViewer();
        });

        Button monitorItem = startMenuItem("Monitor");
        monitorItem.setOnAction(e -> {
            popup.hide();
            onOpenMonitor();
        });

        Button auditLogItem = startMenuItem("Audit Log");
        auditLogItem.setOnAction(e -> {
            popup.hide();
            onOpenAuditLog();
        });

        Button disconnectItem = startMenuItem("Disconnect");
        disconnectItem.setOnAction(e -> {
            popup.hide();
            onDisconnect();
        });

        card.getChildren().addAll(taskManagerItem, terminalItem, logViewerItem, monitorItem, auditLogItem,
                new Separator(), disconnectItem);
        popup.getContent().add(card);
        return popup;
    }

    private Button startMenuItem(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("start-menu-item");
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private ContextMenu createBackgroundContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.setOnAction(e -> navigateTo(currentPath, false));

        Menu newMenu = new Menu("New");
        MenuItem newFolderItem = new MenuItem("Folder...");
        newFolderItem.setOnAction(e -> createNewFolder());
        MenuItem newFileItem = new MenuItem("File...");
        newFileItem.setOnAction(e -> createNewFile());
        newMenu.getItems().addAll(newFolderItem, newFileItem);

        Menu sortMenu = new Menu("Sort by");
        ToggleGroup sortGroup = new ToggleGroup();
        RadioMenuItem sortByName = new RadioMenuItem("Name");
        sortByName.setToggleGroup(sortGroup);
        sortByName.setSelected(sortMode == SortMode.NAME);
        sortByName.setOnAction(e -> applySortMode(SortMode.NAME));
        RadioMenuItem sortBySize = new RadioMenuItem("Size");
        sortBySize.setToggleGroup(sortGroup);
        sortBySize.setSelected(sortMode == SortMode.SIZE);
        sortBySize.setOnAction(e -> applySortMode(SortMode.SIZE));
        sortMenu.getItems().addAll(sortByName, sortBySize);

        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setDisable(clipboardEntry == null);
        pasteItem.setOnAction(e -> pasteClipboard());

        MenuItem terminalItem = new MenuItem("Open Terminal Here");
        terminalItem.setOnAction(e -> openTerminalWindow(currentPath));

        Menu uploadMenu = new Menu("Upload");
        MenuItem uploadFileItem = new MenuItem("File...");
        uploadFileItem.setOnAction(e -> uploadLocalFile());
        MenuItem uploadFolderItem = new MenuItem("Folder...");
        uploadFolderItem.setOnAction(e -> uploadLocalFolder());
        uploadMenu.getItems().addAll(uploadFileItem, uploadFolderItem);

        menu.getItems().addAll(refreshItem, sortMenu, newMenu, uploadMenu, new SeparatorMenuItem(),
                pasteItem, terminalItem);
        return menu;
    }

    private void uploadLocalFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Upload File");
        File file = chooser.showOpenDialog(ownerWindow());
        if (file != null) {
            startUpload(file, currentPath);
        }
    }

    private void uploadLocalFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Upload Folder");
        File dir = chooser.showDialog(ownerWindow());
        if (dir != null) {
            startUpload(dir, currentPath);
        }
    }

    private void startUpload(File localFile, String targetDir) {
        String remotePath = targetDir.endsWith("/") ? targetDir + localFile.getName() : targetDir + "/" + localFile.getName();

        if (localFile.isDirectory()) {
            performUpload(localFile, remotePath);
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                if (sessionManager.exists(remotePath)) {
                    Platform.runLater(() -> confirmOverwriteUpload(localFile, remotePath));
                } else {
                    Platform.runLater(() -> performUpload(localFile, remotePath));
                }
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Upload failed: " + e.getMessage()));
            }
        }, "sftp-upload-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void confirmOverwriteUpload(File localFile, String remotePath) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "\"" + localFile.getName() + "\" already exists in this folder. Replace it?",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.YES) {
                performUpload(localFile, remotePath);
            } else {
                statusLabel.setText("Upload cancelled.");
            }
        });
    }

    private void performUpload(File localFile, String remotePath) {
        statusLabel.setText("Uploading " + localFile.getName() + "...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.upload(localFile, remotePath);
                Platform.runLater(() -> navigateTo(currentPath, false));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Upload failed: " + e.getMessage()));
            }
        }, "sftp-upload");
        worker.setDaemon(true);
        worker.start();
    }

    private void downloadEntry(RemoteEntry entry) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Download Destination");
        File destDir = chooser.showDialog(ownerWindow());
        if (destDir == null) {
            return;
        }
        File localTarget = new File(destDir, entry.name());
        if (!localTarget.exists()) {
            performDownload(entry, localTarget);
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "\"" + entry.name() + "\" already exists at that location. Replace it?",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.YES) {
                performDownload(entry, localTarget);
            } else {
                statusLabel.setText("Download cancelled.");
            }
        });
    }

    private void performDownload(RemoteEntry entry, File localTarget) {
        statusLabel.setText("Downloading " + entry.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.download(entry, localTarget);
                Platform.runLater(() -> statusLabel.setText("Downloaded " + entry.name()));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Download failed: " + e.getMessage()));
            }
        }, "sftp-download");
        worker.setDaemon(true);
        worker.start();
    }

    private Window ownerWindow() {
        return iconGrid.getScene().getWindow();
    }

    private void applySortMode(SortMode mode) {
        sortMode = mode;
        renderEntries(currentEntries);
    }

    private void createNewFolder() {
        TextInputDialog dialog = new TextInputDialog("New Folder");
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("New Folder");
        dialog.setContentText("Folder name:");
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            String path = currentPath.endsWith("/") ? currentPath + trimmed : currentPath + "/" + trimmed;
            statusLabel.setText("Creating folder " + trimmed + "...");

            Thread worker = new Thread(() -> {
                try {
                    sessionManager.createDirectory(path);
                    Platform.runLater(() -> navigateTo(currentPath, false));
                } catch (Exception e) {
                    Platform.runLater(() -> statusLabel.setText("Create folder failed: " + e.getMessage()));
                }
            }, "sftp-mkdir");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void createNewFile() {
        TextInputDialog dialog = new TextInputDialog("New File.txt");
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("New File");
        dialog.setContentText("File name:");
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            String path = currentPath.endsWith("/") ? currentPath + trimmed : currentPath + "/" + trimmed;
            statusLabel.setText("Creating file " + trimmed + "...");

            Thread worker = new Thread(() -> {
                try {
                    sessionManager.writeFile(path, "");
                    Platform.runLater(() -> navigateTo(currentPath, false));
                } catch (Exception e) {
                    Platform.runLater(() -> statusLabel.setText("Create file failed: " + e.getMessage()));
                }
            }, "sftp-touch");
            worker.setDaemon(true);
            worker.start();
        });
    }

    public void init(SshSessionManager sessionManager, ConnectionProfile profile, String rootPath) {
        this.sessionManager = sessionManager;
        this.production = profile.isProduction();
        this.hostKey = profile.getUsername() + "@" + profile.getHost();
        this.auditHost = profile.getHost();
        this.auditUser = profile.getUsername();
        hostLabel.setText(hostKey);
        productionBadge.setVisible(production);
        productionBadge.setManaged(production);
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

    private void onOpenTerminal() {
        openTerminalWindow(null);
    }

    private void openTerminalWindow(String initialDirectory) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linuxdesk/terminal.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 760, 480);
            ThemeManager.apply(scene);

            Stage terminalStage = new Stage();
            terminalStage.initOwner(iconGrid.getScene().getWindow());
            terminalStage.setScene(scene);

            TerminalController controller = loader.getController();
            controller.init(sessionManager, hostLabel.getText(), initialDirectory, terminalStage);

            terminalStage.show();
        } catch (Exception e) {
            statusLabel.setText("Failed to open terminal: " + e.getMessage());
        }
    }

    private void onOpenTaskManager() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linuxdesk/task-manager.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 720, 480);
            ThemeManager.apply(scene);

            Stage taskManagerStage = new Stage();
            taskManagerStage.initOwner(iconGrid.getScene().getWindow());
            taskManagerStage.setScene(scene);

            TaskManagerController controller = loader.getController();
            controller.init(sessionManager, taskManagerStage, auditRecorder);

            taskManagerStage.show();
        } catch (Exception e) {
            statusLabel.setText("Failed to open task manager: " + e.getMessage());
        }
    }

    private void onOpenLogViewer() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linuxdesk/log-viewer.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 820, 520);
            ThemeManager.apply(scene);

            Stage logViewerStage = new Stage();
            logViewerStage.initOwner(iconGrid.getScene().getWindow());
            logViewerStage.setScene(scene);

            LogViewerController controller = loader.getController();
            controller.init(sessionManager, logViewerStage);

            logViewerStage.show();
        } catch (Exception e) {
            statusLabel.setText("Failed to open log viewer: " + e.getMessage());
        }
    }

    private void onOpenMonitor() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linuxdesk/monitor.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 780, 560);
            ThemeManager.apply(scene);

            Stage monitorStage = new Stage();
            monitorStage.initOwner(iconGrid.getScene().getWindow());
            monitorStage.setScene(scene);

            MonitorController controller = loader.getController();
            controller.init(sessionManager, monitorStage);

            monitorStage.show();
        } catch (Exception e) {
            statusLabel.setText("Failed to open monitor: " + e.getMessage());
        }
    }

    private void onDisconnect() {
        logAudit("Disconnect", "success", null);
        sessionManager.close();
        try {
            App.loadScene("/com/linuxdesk/login.fxml", 1040, 420);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to return to login screen", e);
        }
    }

    private void logAudit(String action, String outcome, String detail) {
        auditLogStore.record(auditHost, auditUser, action, outcome, detail);
    }

    private void onOpenAuditLog() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linuxdesk/audit-log.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 900, 520);
            ThemeManager.apply(scene);

            Stage auditStage = new Stage();
            auditStage.initOwner(iconGrid.getScene().getWindow());
            auditStage.setScene(scene);

            AuditLogController controller = loader.getController();
            controller.init(auditStage);

            auditStage.show();
        } catch (Exception e) {
            statusLabel.setText("Failed to open audit log: " + e.getMessage());
        }
    }

    private void navigateTo(String path, boolean pushHistory) {
        if (pushHistory && currentPath != null) {
            history.push(currentPath);
            backButton.setDisable(false);
        }
        boolean directoryChanged = currentPath == null || !currentPath.equals(path);
        currentPath = path;
        pathLabel.setText(path);
        statusLabel.setText("Loading...");
        iconGrid.getChildren().clear();
        if (directoryChanged) {
            currentEntries = List.of();
            hideSearchResults();
            searchField.setText("");
        }

        Thread worker = new Thread(() -> {
            try {
                List<RemoteEntry> entries = sessionManager.listDirectory(path);
                recentPathsStore.recordDirectory(hostKey, path);
                Platform.runLater(() -> renderEntries(entries));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to list directory: " + e.getMessage()));
            }
        }, "sftp-list");
        worker.setDaemon(true);
        worker.start();
    }

    private void renderEntries(List<RemoteEntry> entries) {
        currentEntries = entries;
        List<RemoteEntry> sorted = sortEntries(entries, sortMode);

        iconGrid.getChildren().clear();
        for (RemoteEntry entry : sorted) {
            iconGrid.getChildren().add(createIconNode(entry));
        }
        statusLabel.setText(sorted.size() + " item" + (sorted.size() == 1 ? "" : "s"));
    }

    private static List<RemoteEntry> sortEntries(List<RemoteEntry> entries, SortMode mode) {
        Comparator<RemoteEntry> comparator = mode == SortMode.SIZE
                ? Comparator.comparingLong(RemoteEntry::size)
                : Comparator.comparing(RemoteEntry::name, String.CASE_INSENSITIVE_ORDER);
        return entries.stream()
                .sorted(Comparator.<RemoteEntry>comparingInt(e -> e.directory() ? 0 : 1).thenComparing(comparator))
                .toList();
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
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
                return;
            }
            if (entry.directory()) {
                navigateTo(entry.path(), true);
            } else {
                openFileEditor(entry);
            }
        });

        box.setOnContextMenuRequested(event -> {
            createEntryContextMenu(entry).show(box, event.getScreenX(), event.getScreenY());
            event.consume();
        });

        if (entry.directory()) {
            box.setOnDragOver(event -> {
                if (event.getGestureSource() != box && event.getDragboard().hasFiles()) {
                    event.acceptTransferModes(TransferMode.COPY);
                    event.consume();
                }
            });
            box.setOnDragEntered(event -> {
                if (event.getDragboard().hasFiles()) {
                    box.getStyleClass().add("desktop-icon-drag-over");
                }
            });
            box.setOnDragExited(event -> box.getStyleClass().remove("desktop-icon-drag-over"));
            box.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = db.hasFiles();
                if (success) {
                    for (File file : db.getFiles()) {
                        startUpload(file, entry.path());
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });
        } else {
            box.setOnDragDetected(event -> {
                File tempFile = downloadToTemp(entry);
                if (tempFile != null) {
                    Dragboard db = box.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(List.of(tempFile));
                    db.setContent(content);
                }
                event.consume();
            });
        }

        return box;
    }

    /**
     * Drags a remote file out to Explorer by downloading it to a temp folder first, synchronously,
     * inside the drag gesture — JavaFX's Dragboard needs a real local File before the OS drag can
     * begin, so this isn't a true virtual-file drag (no async delayed rendering); it's a blocking
     * download followed by a normal local-file drag. Fine for typical files, briefly freezes the
     * UI for very large ones. Directories aren't supported this way — drag-out is files only.
     */
    private File downloadToTemp(RemoteEntry entry) {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "linuxdesk-drag");
            tempDir.mkdirs();
            File tempFile = new File(tempDir, entry.name());
            statusLabel.setText("Preparing " + entry.name() + " to drag...");
            sessionManager.download(entry, tempFile);
            statusLabel.setText("Drag " + entry.name() + " to drop it.");
            return tempFile;
        } catch (Exception e) {
            statusLabel.setText("Drag failed: " + e.getMessage());
            return null;
        }
    }

    private ContextMenu createEntryContextMenu(RemoteEntry entry) {
        ContextMenu menu = new ContextMenu();

        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(e -> {
            clipboardEntry = entry;
            statusLabel.setText("Copied " + entry.name());
        });

        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setDisable(clipboardEntry == null);
        pasteItem.setOnAction(e -> pasteClipboard());

        MenuItem renameItem = new MenuItem("Rename");
        renameItem.setOnAction(e -> renameEntry(entry));

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteEntry(entry));

        MenuItem downloadItem = new MenuItem("Download...");
        downloadItem.setOnAction(e -> downloadEntry(entry));

        MenuItem permissionsItem = new MenuItem("Permissions...");
        permissionsItem.setOnAction(e -> PermissionsDialog.show(sessionManager, entry, ownerWindow(),
                statusLabel::setText, () -> navigateTo(currentPath, false), auditRecorder));

        Menu compressMenu = new Menu("Compress to");
        MenuItem zipItem = new MenuItem("Zip");
        zipItem.setOnAction(e -> compressEntry(entry, ArchiveFormat.ZIP));
        MenuItem tarGzItem = new MenuItem("tar.gz");
        tarGzItem.setOnAction(e -> compressEntry(entry, ArchiveFormat.TAR_GZ));
        compressMenu.getItems().addAll(zipItem, tarGzItem);

        menu.getItems().addAll(copyItem, pasteItem, renameItem, deleteItem, new SeparatorMenuItem(),
                downloadItem, compressMenu, permissionsItem);

        if (!entry.directory() && isArchiveName(entry.name())) {
            MenuItem extractItem = new MenuItem("Extract Here");
            extractItem.setOnAction(e -> extractEntry(entry));
            menu.getItems().add(extractItem);
        }

        return menu;
    }

    private static boolean isArchiveName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tar.xz") || lower.endsWith(".txz");
    }

    private static String stripArchiveExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String[] suffixes = {".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".txz", ".zip", ".tar"};
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name + "-extracted";
    }

    private void compressEntry(RemoteEntry entry, ArchiveFormat format) {
        String extension = format == ArchiveFormat.ZIP ? ".zip" : ".tar.gz";
        String archiveName = entry.name() + extension;
        String archivePath = currentPath.endsWith("/") ? currentPath + archiveName : currentPath + "/" + archiveName;
        String parentDir = currentPath;

        Thread worker = new Thread(() -> {
            try {
                if (sessionManager.exists(archivePath)) {
                    Platform.runLater(() -> confirmOverwriteCompress(entry, format, archiveName, parentDir));
                } else {
                    Platform.runLater(() -> performCompress(entry, format, archiveName, parentDir));
                }
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Compress failed: " + e.getMessage()));
            }
        }, "sftp-compress-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void confirmOverwriteCompress(RemoteEntry entry, ArchiveFormat format, String archiveName, String parentDir) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "\"" + archiveName + "\" already exists in this folder. Replace it?",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.YES) {
                performCompress(entry, format, archiveName, parentDir);
            } else {
                statusLabel.setText("Compress cancelled.");
            }
        });
    }

    private void performCompress(RemoteEntry entry, ArchiveFormat format, String archiveName, String parentDir) {
        statusLabel.setText("Compressing " + entry.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.compress(parentDir, entry.name(), archiveName, format);
                Platform.runLater(() -> navigateTo(currentPath, false));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Compress failed: " + e.getMessage()));
            }
        }, "sftp-compress");
        worker.setDaemon(true);
        worker.start();
    }

    private void extractEntry(RemoteEntry entry) {
        String destName = stripArchiveExtension(entry.name());
        String destPath = currentPath.endsWith("/") ? currentPath + destName : currentPath + "/" + destName;

        Thread worker = new Thread(() -> {
            try {
                if (sessionManager.exists(destPath)) {
                    Platform.runLater(() -> confirmOverwriteExtract(entry, destPath));
                } else {
                    Platform.runLater(() -> performExtract(entry, destPath));
                }
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Extract failed: " + e.getMessage()));
            }
        }, "sftp-extract-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void confirmOverwriteExtract(RemoteEntry entry, String destPath) {
        String destName = destPath.substring(destPath.lastIndexOf('/') + 1);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "A folder named \"" + destName + "\" already exists. Extract into it anyway (matching files may be overwritten)?",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.YES) {
                performExtract(entry, destPath);
            } else {
                statusLabel.setText("Extract cancelled.");
            }
        });
    }

    private void performExtract(RemoteEntry entry, String destPath) {
        statusLabel.setText("Extracting " + entry.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.extractArchive(entry.path(), destPath);
                Platform.runLater(() -> navigateTo(currentPath, false));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Extract failed: " + e.getMessage()));
            }
        }, "sftp-extract");
        worker.setDaemon(true);
        worker.start();
    }

    private void pasteClipboard() {
        if (clipboardEntry == null) {
            return;
        }
        RemoteEntry source = clipboardEntry;
        String targetDir = currentPath;
        statusLabel.setText("Pasting " + source.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                String destPath = buildPastePath(source, targetDir);
                sessionManager.copy(source, destPath);
                Platform.runLater(() -> navigateTo(currentPath, false));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Paste failed: " + e.getMessage()));
            }
        }, "sftp-copy");
        worker.setDaemon(true);
        worker.start();
    }

    private String buildPastePath(RemoteEntry source, String targetDir) {
        String base = targetDir.endsWith("/") ? targetDir + source.name() : targetDir + "/" + source.name();
        if (!sessionManager.exists(base)) {
            return base;
        }
        String stem = source.name();
        String ext = "";
        int dot = stem.lastIndexOf('.');
        if (!source.directory() && dot > 0) {
            ext = stem.substring(dot);
            stem = stem.substring(0, dot);
        }
        int counter = 1;
        String candidate;
        do {
            String suffix = counter == 1 ? " (copy)" : " (copy " + counter + ")";
            String candidateName = stem + suffix + ext;
            candidate = targetDir.endsWith("/") ? targetDir + candidateName : targetDir + "/" + candidateName;
            counter++;
        } while (sessionManager.exists(candidate));
        return candidate;
    }

    private void renameEntry(RemoteEntry entry) {
        TextInputDialog dialog = new TextInputDialog(entry.name());
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("Rename");
        dialog.setContentText("New name:");
        dialog.showAndWait().ifPresent(newName -> {
            String trimmed = newName.trim();
            if (trimmed.isEmpty() || trimmed.equals(entry.name())) {
                return;
            }
            String parent = currentPath;
            String newPath = parent.endsWith("/") ? parent + trimmed : parent + "/" + trimmed;
            performRename(entry, newPath, trimmed);
        });
    }

    private void performRename(RemoteEntry entry, String newPath, String newName) {
        statusLabel.setText("Renaming " + entry.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                if (sessionManager.exists(newPath)) {
                    Platform.runLater(() -> confirmOverwriteRename(entry, newPath, newName));
                    return;
                }
                sessionManager.rename(entry.path(), newPath);
                Platform.runLater(() -> navigateTo(currentPath, false));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Rename failed: " + e.getMessage()));
            }
        }, "sftp-rename");
        worker.setDaemon(true);
        worker.start();
    }

    private void confirmOverwriteRename(RemoteEntry entry, String newPath, String newName) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "\"" + newName + "\" already exists in this folder. Replace it?",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button != ButtonType.YES) {
                statusLabel.setText("Rename cancelled.");
                return;
            }
            statusLabel.setText("Renaming " + entry.name() + "...");

            Thread worker = new Thread(() -> {
                try {
                    sessionManager.renameOverwrite(entry.path(), newPath);
                    Platform.runLater(() -> navigateTo(currentPath, false));
                } catch (Exception e) {
                    Platform.runLater(() -> statusLabel.setText("Rename failed: " + e.getMessage()));
                }
            }, "sftp-rename-overwrite");
            worker.setDaemon(true);
            worker.start();
        });
    }

    private void deleteEntry(RemoteEntry entry) {
        if (production && entry.directory()) {
            confirmProductionDelete(entry);
        } else {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + entry.name() + "? This cannot be undone.",
                    ButtonType.YES, ButtonType.NO);
            ThemeManager.apply(confirm.getDialogPane());
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(button -> {
                if (button == ButtonType.YES) {
                    performDelete(entry);
                }
            });
        }
    }

    /** Production-tagged profiles require typing the folder name for a recursive delete, not just a click-through. */
    private void confirmProductionDelete(RemoteEntry entry) {
        TextInputDialog dialog = new TextInputDialog();
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("Confirm delete on production host");
        dialog.setContentText("This is a PRODUCTION host. Deleting \"" + entry.name()
                + "\" removes it and everything inside it, permanently.\n"
                + "Type the folder name to confirm:");
        dialog.showAndWait().ifPresent(typed -> {
            if (typed.trim().equals(entry.name())) {
                performDelete(entry);
            } else {
                statusLabel.setText("Confirmation text didn't match — delete cancelled.");
            }
        });
    }

    private void performDelete(RemoteEntry entry) {
        statusLabel.setText("Deleting " + entry.name() + "...");

        String kind = entry.directory() ? "folder" : "file";
        Thread worker = new Thread(() -> {
            try {
                sessionManager.delete(entry);
                logAudit("Delete " + kind + " " + entry.path(), "success", null);
                Platform.runLater(() -> {
                    if (entry.equals(clipboardEntry)) {
                        clipboardEntry = null;
                    }
                    navigateTo(currentPath, false);
                });
            } catch (Exception e) {
                logAudit("Delete " + kind + " " + entry.path(), "failure", e.getMessage());
                Platform.runLater(() -> statusLabel.setText("Delete failed: " + e.getMessage()));
            }
        }, "sftp-delete");
        worker.setDaemon(true);
        worker.start();
    }

    private void openFileEditor(RemoteEntry entry) {
        if (entry.size() > MAX_EDITABLE_SIZE) {
            statusLabel.setText("File too large to edit: " + entry.name());
            return;
        }
        statusLabel.setText("Opening " + entry.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                String content = sessionManager.readFile(entry.path());
                recentPathsStore.recordFile(hostKey, entry.path());
                Platform.runLater(() -> showEditor(entry, content));
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to open file: " + e.getMessage()));
            }
        }, "sftp-read");
        worker.setDaemon(true);
        worker.start();
    }

    private void showEditor(RemoteEntry entry, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/linuxdesk/editor.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root, 800, 600);
            ThemeManager.apply(scene);

            Stage editorStage = new Stage();
            editorStage.setTitle(entry.name() + " — LinuxDesk");
            editorStage.initOwner(iconGrid.getScene().getWindow());
            editorStage.setScene(scene);

            EditorController controller = loader.getController();
            controller.init(sessionManager, entry, content, editorStage);

            editorStage.show();
            statusLabel.setText("Editing " + entry.name());
        } catch (Exception e) {
            statusLabel.setText("Failed to open editor: " + e.getMessage());
        }
    }
}
