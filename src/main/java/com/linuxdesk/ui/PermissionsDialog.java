package com.linuxdesk.ui;

import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.RemotePermissions;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.function.Consumer;

/** Modal chmod/chown dialog: checkbox matrix, live octal entry, special bits, and recursive apply. */
final class PermissionsDialog {

    private PermissionsDialog() {
    }

    static void show(SshSessionManager sessionManager, RemoteEntry entry, Window owner,
                      Consumer<String> statusUpdater, Runnable onApplied) {
        statusUpdater.accept("Loading permissions for " + entry.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                RemotePermissions current = sessionManager.getPermissions(entry.path());
                Platform.runLater(() -> {
                    statusUpdater.accept("");
                    open(sessionManager, entry, owner, current, statusUpdater, onApplied);
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusUpdater.accept("Failed to load permissions: " + e.getMessage()));
            }
        }, "sftp-permissions-load");
        worker.setDaemon(true);
        worker.start();
    }

    private static void open(SshSessionManager sessionManager, RemoteEntry entry, Window owner,
                              RemotePermissions current, Consumer<String> statusUpdater, Runnable onApplied) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Permissions — " + entry.name());
        dialog.setHeaderText(null);
        ThemeManager.apply(dialog.getDialogPane());

        ButtonType applyButtonType = new ButtonType("Apply", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(6);
        grid.add(new Label("Read"), 1, 0);
        grid.add(new Label("Write"), 2, 0);
        grid.add(new Label("Execute"), 3, 0);

        CheckBox ownerRead = new CheckBox();
        CheckBox ownerWrite = new CheckBox();
        CheckBox ownerExec = new CheckBox();
        CheckBox groupRead = new CheckBox();
        CheckBox groupWrite = new CheckBox();
        CheckBox groupExec = new CheckBox();
        CheckBox otherRead = new CheckBox();
        CheckBox otherWrite = new CheckBox();
        CheckBox otherExec = new CheckBox();

        grid.add(new Label("Owner"), 0, 1);
        grid.add(ownerRead, 1, 1);
        grid.add(ownerWrite, 2, 1);
        grid.add(ownerExec, 3, 1);

        grid.add(new Label("Group"), 0, 2);
        grid.add(groupRead, 1, 2);
        grid.add(groupWrite, 2, 2);
        grid.add(groupExec, 3, 2);

        grid.add(new Label("Other"), 0, 3);
        grid.add(otherRead, 1, 3);
        grid.add(otherWrite, 2, 3);
        grid.add(otherExec, 3, 3);

        CheckBox setuidBox = new CheckBox("setuid");
        setuidBox.setTooltip(new Tooltip("Run as the file's owner, not the invoking user"));
        CheckBox setgidBox = new CheckBox("setgid");
        setgidBox.setTooltip(new Tooltip("Run as the file's group; on a directory, new files inherit its group"));
        CheckBox stickyBox = new CheckBox("sticky");
        stickyBox.setTooltip(new Tooltip("In a shared directory, only the owner can delete/rename their own files"));
        HBox specialRow = new HBox(14, setuidBox, setgidBox, stickyBox);

        TextField octalField = new TextField();
        octalField.setPrefColumnCount(4);
        HBox octalRow = new HBox(8, new Label("Octal:"), octalField);
        octalRow.setAlignment(Pos.CENTER_LEFT);

        Label warningLabel = new Label();
        warningLabel.getStyleClass().add("permissions-warning");
        warningLabel.setWrapText(true);
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);

        TextField ownerField = new TextField(current.owner());
        TextField groupField = new TextField(current.group());
        GridPane ownerGrid = new GridPane();
        ownerGrid.setHgap(10);
        ownerGrid.setVgap(6);
        ownerGrid.add(new Label("Owner:"), 0, 0);
        ownerGrid.add(ownerField, 1, 0);
        ownerGrid.add(new Label("Group:"), 0, 1);
        ownerGrid.add(groupField, 1, 1);

        CheckBox recursiveBox = new CheckBox("Apply recursively to contents");
        recursiveBox.setDisable(!entry.directory());

        VBox content = new VBox(14, grid, specialRow, octalRow, warningLabel, new Separator(), ownerGrid, recursiveBox);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);

        CheckBox[] allBoxes = {ownerRead, ownerWrite, ownerExec, groupRead, groupWrite, groupExec,
                otherRead, otherWrite, otherExec, setuidBox, setgidBox, stickyBox};
        boolean[] syncing = {false};

        Runnable updateOctalFromBoxes = () -> {
            int ownerDigit = (ownerRead.isSelected() ? 4 : 0) + (ownerWrite.isSelected() ? 2 : 0) + (ownerExec.isSelected() ? 1 : 0);
            int groupDigit = (groupRead.isSelected() ? 4 : 0) + (groupWrite.isSelected() ? 2 : 0) + (groupExec.isSelected() ? 1 : 0);
            int otherDigit = (otherRead.isSelected() ? 4 : 0) + (otherWrite.isSelected() ? 2 : 0) + (otherExec.isSelected() ? 1 : 0);
            int specialDigit = (setuidBox.isSelected() ? 4 : 0) + (setgidBox.isSelected() ? 2 : 0) + (stickyBox.isSelected() ? 1 : 0);
            syncing[0] = true;
            octalField.setText("" + specialDigit + ownerDigit + groupDigit + otherDigit);
            syncing[0] = false;
            updateWarning(warningLabel, otherWrite.isSelected(), setuidBox.isSelected(),
                    ownerExec.isSelected() || groupExec.isSelected() || otherExec.isSelected());
        };

        for (CheckBox box : allBoxes) {
            box.selectedProperty().addListener((obs, old, val) -> {
                if (!syncing[0]) {
                    updateOctalFromBoxes.run();
                }
            });
        }

        octalField.textProperty().addListener((obs, old, text) -> {
            if (syncing[0]) {
                return;
            }
            String digits = text.trim();
            if (!digits.matches("[0-7]{3,4}")) {
                return;
            }
            String padded = digits.length() == 3 ? "0" + digits : digits;
            int special = Character.digit(padded.charAt(0), 8);
            int ownerDigit = Character.digit(padded.charAt(1), 8);
            int groupDigit = Character.digit(padded.charAt(2), 8);
            int otherDigit = Character.digit(padded.charAt(3), 8);
            syncing[0] = true;
            ownerRead.setSelected((ownerDigit & 4) != 0);
            ownerWrite.setSelected((ownerDigit & 2) != 0);
            ownerExec.setSelected((ownerDigit & 1) != 0);
            groupRead.setSelected((groupDigit & 4) != 0);
            groupWrite.setSelected((groupDigit & 2) != 0);
            groupExec.setSelected((groupDigit & 1) != 0);
            otherRead.setSelected((otherDigit & 4) != 0);
            otherWrite.setSelected((otherDigit & 2) != 0);
            otherExec.setSelected((otherDigit & 1) != 0);
            setuidBox.setSelected((special & 4) != 0);
            setgidBox.setSelected((special & 2) != 0);
            stickyBox.setSelected((special & 1) != 0);
            syncing[0] = false;
            updateWarning(warningLabel, otherWrite.isSelected(), setuidBox.isSelected(),
                    ownerExec.isSelected() || groupExec.isSelected() || otherExec.isSelected());
        });

        octalField.setText(normalizeOctal(current.octal()));

        dialog.showAndWait().ifPresent(button -> {
            if (button != applyButtonType) {
                return;
            }
            String newOctal = octalField.getText().trim();
            if (!newOctal.matches("[0-7]{3,4}")) {
                statusUpdater.accept("Invalid octal permissions.");
                return;
            }
            String newOwner = ownerField.getText().trim();
            String newGroup = groupField.getText().trim();
            boolean recursive = recursiveBox.isSelected();
            boolean ownershipChanged = !newOwner.equals(current.owner()) || !newGroup.equals(current.group());

            if (recursive) {
                confirmRecursiveThenApply(sessionManager, entry, newOctal, newOwner, newGroup,
                        ownershipChanged, statusUpdater, onApplied);
            } else {
                applyChanges(sessionManager, entry, newOctal, newOwner, newGroup, ownershipChanged, false, statusUpdater, onApplied);
            }
        });
    }

    private static void updateWarning(Label warningLabel, boolean otherWritable, boolean setuid, boolean anyExecutable) {
        StringBuilder sb = new StringBuilder();
        if (otherWritable) {
            sb.append("World-writable — anyone can modify this. ");
        }
        if (setuid && anyExecutable) {
            sb.append("setuid on an executable runs it with the owner's privileges — high risk unless intentional.");
        }
        String text = sb.toString().trim();
        warningLabel.setText(text);
        warningLabel.setVisible(!text.isEmpty());
        warningLabel.setManaged(!text.isEmpty());
    }

    private static String normalizeOctal(String octal) {
        String trimmed = octal.trim();
        return trimmed.length() == 3 ? "0" + trimmed : trimmed;
    }

    private static void confirmRecursiveThenApply(SshSessionManager sessionManager, RemoteEntry entry, String octal,
            String owner, String group, boolean ownershipChanged, Consumer<String> statusUpdater, Runnable onApplied) {
        statusUpdater.accept("Counting affected items...");

        Thread worker = new Thread(() -> {
            int count;
            try {
                count = sessionManager.countTree(entry.path());
            } catch (Exception e) {
                count = -1;
            }
            int finalCount = count;
            Platform.runLater(() -> {
                String message = finalCount >= 0
                        ? "This will change permissions on " + finalCount + " item(s) inside \"" + entry.name() + "\". Continue?"
                        : "This will recursively change permissions inside \"" + entry.name() + "\". Continue?";
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
                ThemeManager.apply(confirm.getDialogPane());
                confirm.setHeaderText(null);
                confirm.showAndWait().ifPresent(button -> {
                    if (button == ButtonType.YES) {
                        applyChanges(sessionManager, entry, octal, owner, group, ownershipChanged, true, statusUpdater, onApplied);
                    } else {
                        statusUpdater.accept("Permissions change cancelled.");
                    }
                });
            });
        }, "sftp-permissions-count");
        worker.setDaemon(true);
        worker.start();
    }

    private static void applyChanges(SshSessionManager sessionManager, RemoteEntry entry, String octal, String owner,
            String group, boolean ownershipChanged, boolean recursive, Consumer<String> statusUpdater, Runnable onApplied) {
        statusUpdater.accept("Applying permissions to " + entry.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.setPermissions(entry.path(), octal, recursive);
                if (ownershipChanged) {
                    sessionManager.setOwnership(entry.path(), owner, group, recursive);
                }
                Platform.runLater(() -> {
                    statusUpdater.accept("Permissions updated.");
                    onApplied.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusUpdater.accept("Permissions update failed: " + e.getMessage()));
            }
        }, "sftp-permissions-apply");
        worker.setDaemon(true);
        worker.start();
    }
}
