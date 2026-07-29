package com.linuxdesk.ui;

import com.linuxdesk.App;
import com.linuxdesk.model.ConnectionProfile;
import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
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
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

public class DesktopController {

    private static final long MAX_EDITABLE_SIZE = 2 * 1024 * 1024;

    @FXML private Label hostLabel;
    @FXML private Label pathLabel;
    @FXML private Button backButton;
    @FXML private FlowPane iconGrid;
    @FXML private ScrollPane scrollPane;
    @FXML private Label statusLabel;

    private enum SortMode { NAME, SIZE }

    private SshSessionManager sessionManager;
    private final Deque<String> history = new ArrayDeque<>();
    private String currentPath;
    private RemoteEntry clipboardEntry;
    private List<RemoteEntry> currentEntries = List.of();
    private SortMode sortMode = SortMode.NAME;

    @FXML
    private void initialize() {
        scrollPane.setOnContextMenuRequested(event -> {
            createBackgroundContextMenu().show(scrollPane, event.getScreenX(), event.getScreenY());
        });
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
            startUpload(file);
        }
    }

    private void uploadLocalFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Upload Folder");
        File dir = chooser.showDialog(ownerWindow());
        if (dir != null) {
            startUpload(dir);
        }
    }

    private void startUpload(File localFile) {
        String targetDir = currentPath;
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

    @FXML
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
            controller.init(sessionManager, taskManagerStage);

            taskManagerStage.show();
        } catch (Exception e) {
            statusLabel.setText("Failed to open task manager: " + e.getMessage());
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
        currentEntries = entries;
        List<RemoteEntry> sorted = sortEntries(entries, sortMode);

        iconGrid.getChildren().clear();
        for (RemoteEntry entry : sorted) {
            iconGrid.getChildren().add(createIconNode(entry));
        }
        statusLabel.setText(entries.size() + " item" + (entries.size() == 1 ? "" : "s"));
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

        return box;
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

        menu.getItems().addAll(copyItem, pasteItem, renameItem, deleteItem, new SeparatorMenuItem(), downloadItem);
        return menu;
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
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete " + entry.name() + "? This cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button != ButtonType.YES) {
                return;
            }
            statusLabel.setText("Deleting " + entry.name() + "...");

            Thread worker = new Thread(() -> {
                try {
                    sessionManager.delete(entry);
                    Platform.runLater(() -> {
                        if (entry.equals(clipboardEntry)) {
                            clipboardEntry = null;
                        }
                        navigateTo(currentPath, false);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> statusLabel.setText("Delete failed: " + e.getMessage()));
                }
            }, "sftp-delete");
            worker.setDaemon(true);
            worker.start();
        });
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
