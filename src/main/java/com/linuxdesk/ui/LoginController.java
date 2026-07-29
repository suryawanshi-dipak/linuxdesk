package com.linuxdesk.ui;

import com.linuxdesk.App;
import com.linuxdesk.model.ConnectionProfile;
import com.linuxdesk.profile.ProfileStore;
import com.linuxdesk.ssh.HostKeyPrompt;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class LoginController {

    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField usernameField;
    @FXML private TextField keyPathField;
    @FXML private PasswordField passphraseField;
    @FXML private Label commandPreviewLabel;
    @FXML private Label statusLabel;
    @FXML private Button testConnectionButton;
    @FXML private Button browseButton;

    private final ProfileStore profileStore = new ProfileStore();

    @FXML
    private void initialize() {
        ConnectionProfile saved = profileStore.load();
        hostField.setText(saved.getHost());
        portField.setText(String.valueOf(saved.getPort()));
        usernameField.setText(saved.getUsername());
        keyPathField.setText(saved.getPrivateKeyPath());

        for (TextField field : new TextField[]{hostField, portField, usernameField, keyPathField}) {
            field.textProperty().addListener((obs, oldVal, newVal) -> updateCommandPreview());
        }
        updateCommandPreview();
    }

    @FXML
    private void onBrowseKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select private key file");
        File initialDir = new File(System.getProperty("user.home"), ".ssh");
        if (initialDir.isDirectory()) {
            chooser.setInitialDirectory(initialDir);
        }
        File selected = chooser.showOpenDialog(browseButton.getScene().getWindow());
        if (selected != null) {
            keyPathField.setText(selected.getAbsolutePath());
        }
    }

    @FXML
    private void onSaveProfile() {
        try {
            profileStore.save(currentProfile());
            showStatus("Profile saved.", false);
        } catch (Exception e) {
            showStatus("Could not save profile: " + e.getMessage(), true);
        }
    }

    @FXML
    private void onTestConnection() {
        ConnectionProfile profile = currentProfile();
        String passphrase = passphraseField.getText();

        testConnectionButton.setDisable(true);
        showStatus("Connecting...", false);

        Thread worker = new Thread(() -> attemptConnect(profile, passphrase), "ssh-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void attemptConnect(ConnectionProfile profile, String passphrase) {
        SshSessionManager sessionManager = new SshSessionManager();
        try {
            sessionManager.connect(profile.getHost(), profile.getPort(), profile.getUsername(),
                    profile.getPrivateKeyPath(), passphrase, createHostKeyPrompt());
            String rootPath = sessionManager.resolveHomeDirectory();

            Platform.runLater(() -> {
                try {
                    DesktopController controller = App.loadScene("/com/linuxdesk/desktop.fxml", 1024, 680);
                    controller.init(sessionManager, profile, rootPath);
                } catch (Exception e) {
                    showStatus("Connected, but failed to open desktop: " + e.getMessage(), true);
                    testConnectionButton.setDisable(false);
                    sessionManager.close();
                }
            });
        } catch (Exception e) {
            sessionManager.close();
            Platform.runLater(() -> {
                showStatus("Connection failed: " + e.getMessage(), true);
                testConnectionButton.setDisable(false);
            });
        }
    }

    private HostKeyPrompt createHostKeyPrompt() {
        return new HostKeyPrompt() {
            @Override
            public boolean confirmUnknownHost(String host, int port, String keyType, String sha256Fingerprint, String md5Fingerprint) {
                return showUnknownHostDialog(host, port, keyType, sha256Fingerprint, md5Fingerprint);
            }

            @Override
            public boolean confirmChangedHost(String host, int port, String keyType, String previousFingerprint, String presentedFingerprint) {
                return showChangedHostDialog(host, port, keyType, previousFingerprint, presentedFingerprint);
            }
        };
    }

    /** First-time connection to a host: not yet in the known_hosts store. Runs on the ssh-connect thread; blocks it. */
    private boolean showUnknownHostDialog(String host, int port, String keyType, String sha256Fingerprint, String md5Fingerprint) {
        return awaitFxResult(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Unrecognized host");
            alert.setHeaderText("You're connecting to " + host + ":" + port + " for the first time.");

            Label instructions = new Label(
                    "Compare this fingerprint with one obtained from the server admin through a "
                            + "trusted channel (not this connection). If it matches, it's safe to continue.");
            instructions.setWrapText(true);
            instructions.setMaxWidth(420);

            Label fingerprintBox = new Label(
                    "Key type: " + keyType + "\nSHA256:   " + sha256Fingerprint + "\nMD5:      " + md5Fingerprint);
            fingerprintBox.getStyleClass().add("host-key-fingerprint");

            VBox content = new VBox(10, instructions, fingerprintBox);
            alert.getDialogPane().setContent(content);
            alert.getDialogPane().getStyleClass().add("host-key-dialog");

            ButtonType trustType = new ButtonType("Trust and Connect", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(cancelType, trustType);
            ThemeManager.apply(alert.getDialogPane());
            ((Button) alert.getDialogPane().lookupButton(cancelType)).setDefaultButton(true);
            ((Button) alert.getDialogPane().lookupButton(trustType)).setDefaultButton(false);

            Optional<ButtonType> choice = alert.showAndWait();
            return choice.isPresent() && choice.get() == trustType;
        });
    }

    /**
     * A previously trusted host presented a DIFFERENT key. Either the server was rebuilt/rotated
     * its key, or this is a man-in-the-middle attack. Default action is reject; accepting requires
     * explicitly checking a confirmation box first (deliberate friction, not a routine "click OK").
     */
    private boolean showChangedHostDialog(String host, int port, String keyType, String previousFingerprint, String presentedFingerprint) {
        return awaitFxResult(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Host key changed");
            alert.setHeaderText("⚠ The host key for " + host + ":" + port + " does NOT match the one saved previously.");

            Label warning = new Label(
                    "This can happen when a server is reinstalled or its key is rotated deliberately — "
                            + "but it is also exactly what a man-in-the-middle attack looks like. "
                            + "Only continue if you are certain this change is expected.");
            warning.setWrapText(true);
            warning.setMaxWidth(440);
            warning.getStyleClass().add("host-key-warning-text");

            Label fingerprintBox = new Label(
                    "Key type:            " + keyType
                            + "\nPreviously trusted:  " + previousFingerprint
                            + "\nNow presented:       " + presentedFingerprint);
            fingerprintBox.getStyleClass().add("host-key-fingerprint");

            CheckBox confirmCheck = new CheckBox("I have verified this key change is expected and safe.");

            VBox content = new VBox(10, warning, fingerprintBox, confirmCheck);
            alert.getDialogPane().setContent(content);
            alert.getDialogPane().getStyleClass().addAll("host-key-dialog", "host-key-danger");

            ButtonType trustType = new ButtonType("Trust New Key (unsafe)", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancelType = new ButtonType("Cancel — Stay Safe", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(cancelType, trustType);
            ThemeManager.apply(alert.getDialogPane());

            Button trustButton = (Button) alert.getDialogPane().lookupButton(trustType);
            Button cancelButton = (Button) alert.getDialogPane().lookupButton(cancelType);
            trustButton.getStyleClass().add("danger-accept-button");
            trustButton.setDefaultButton(false);
            trustButton.setDisable(true);
            cancelButton.setDefaultButton(true);
            confirmCheck.selectedProperty().addListener((obs, was, isSelected) -> trustButton.setDisable(!isSelected));

            Optional<ButtonType> choice = alert.showAndWait();
            return choice.isPresent() && choice.get() == trustType;
        });
    }

    /** Runs {@code onFxThread} on the FX thread and blocks the calling (background) thread for its result. */
    private boolean awaitFxResult(java.util.function.Supplier<Boolean> onFxThread) {
        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(onFxThread.get());
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private ConnectionProfile currentProfile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setHost(hostField.getText().trim());
        profile.setPort(parsePort(portField.getText()));
        profile.setUsername(usernameField.getText().trim());
        profile.setPrivateKeyPath(keyPathField.getText().trim());
        return profile;
    }

    private int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 22;
        }
    }

    private void updateCommandPreview() {
        commandPreviewLabel.setText(currentProfile().toSshCommand());
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().removeAll("status-ok", "status-error");
        statusLabel.getStyleClass().add(error ? "status-error" : "status-ok");
    }
}
