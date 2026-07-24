package com.linuxdesk.ui;

import com.linuxdesk.App;
import com.linuxdesk.model.ConnectionProfile;
import com.linuxdesk.profile.ProfileStore;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;

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
                    profile.getPrivateKeyPath(), passphrase);
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
