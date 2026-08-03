package com.linuxdesk.ui;

import com.linuxdesk.App;
import com.linuxdesk.audit.AuditLogStore;
import com.linuxdesk.model.ConnectionHistoryEntry;
import com.linuxdesk.model.ConnectionProfile;
import com.linuxdesk.model.ConnectionProfile.AuthMethod;
import com.linuxdesk.profile.ConnectionHistoryStore;
import com.linuxdesk.profile.ProfileStore;
import com.linuxdesk.ssh.HostKeyPrompt;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class LoginController {

    private static final String[] COLOR_PALETTE = {
            ConnectionProfile.DEFAULT_COLOR, // gray / no tag
            "#3d7bd9", // blue
            "#2ea043", // green
            "#9b59b6", // purple
            "#e5a13d", // orange
            "#e5657a", // red
    };

    @FXML private ToggleButton profilesToggle;
    @FXML private ToggleButton recentToggle;
    @FXML private TextField profileSearchField;
    @FXML private ListView<Object> profileListView;
    @FXML private Button newProfileButton;
    @FXML private Button duplicateProfileButton;
    @FXML private Button deleteProfileButton;

    @FXML private TextField nameField;
    @FXML private HBox colorSwatchRow;
    @FXML private CheckBox productionCheck;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField usernameField;
    @FXML private ToggleButton keyAuthToggle;
    @FXML private ToggleButton passwordAuthToggle;
    @FXML private HBox keyAuthRow;
    @FXML private HBox passwordAuthRow;
    @FXML private TextField keyPathField;
    @FXML private PasswordField passphraseField;
    @FXML private PasswordField loginPasswordField;
    @FXML private Label commandPreviewLabel;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Button testConnectionButton;
    @FXML private Button browseButton;

    private final ProfileStore profileStore = new ProfileStore();
    private final ConnectionHistoryStore historyStore = new ConnectionHistoryStore();
    private final AuditLogStore auditLogStore = new AuditLogStore();
    private final ObservableList<ConnectionProfile> profiles = FXCollections.observableArrayList();
    private final ObservableList<ConnectionHistoryEntry> historyEntries = FXCollections.observableArrayList();
    private FilteredList<ConnectionProfile> filteredProfiles;
    private FilteredList<ConnectionHistoryEntry> filteredHistory;
    private final ToggleGroup colorToggleGroup = new ToggleGroup();

    /** Id of the profile currently loaded into the form; null while editing an unsaved new profile. */
    private String editingProfileId;
    private String selectedColor = ConnectionProfile.DEFAULT_COLOR;
    private boolean suppressSelectionEvents;
    private boolean showingRecent;

    @FXML
    private void initialize() {
        buildColorSwatches();
        setupModeToggle();
        setupAuthMethodToggle();
        setupProfileList();

        for (TextField field : new TextField[]{hostField, portField, usernameField, keyPathField}) {
            field.textProperty().addListener((obs, oldVal, newVal) -> updateCommandPreview());
        }

        profiles.setAll(profileStore.loadAll());
        historyEntries.setAll(historyStore.loadAll());

        String lastUsedId = profileStore.loadLastUsedId();
        ConnectionProfile initial = profiles.stream()
                .filter(p -> p.getId().equals(lastUsedId))
                .findFirst()
                .orElse(profiles.isEmpty() ? null : profiles.get(0));
        if (initial != null) {
            profileListView.getSelectionModel().select(initial);
        } else {
            onNewProfile();
        }

        updateCommandPreview();
    }

    private void buildColorSwatches() {
        for (String hex : COLOR_PALETTE) {
            ToggleButton swatch = new ToggleButton();
            swatch.getStyleClass().add("color-swatch");
            swatch.setStyle("-fx-background-color: " + hex + ";");
            swatch.setUserData(hex);
            swatch.setToggleGroup(colorToggleGroup);
            colorSwatchRow.getChildren().add(swatch);
        }
        colorToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                colorToggleGroup.selectToggle(oldToggle);
            } else {
                selectedColor = (String) newToggle.getUserData();
            }
        });
        colorToggleGroup.selectToggle((ToggleButton) colorSwatchRow.getChildren().get(0));
    }

    private void setSelectedColorTag(String hex) {
        String resolved = (hex == null || hex.isBlank()) ? ConnectionProfile.DEFAULT_COLOR : hex;
        selectedColor = resolved;
        for (var node : colorSwatchRow.getChildren()) {
            ToggleButton swatch = (ToggleButton) node;
            if (resolved.equals(swatch.getUserData())) {
                colorToggleGroup.selectToggle(swatch);
                return;
            }
        }
        colorToggleGroup.selectToggle(null);
    }

    private void setupModeToggle() {
        ToggleGroup viewGroup = new ToggleGroup();
        profilesToggle.setToggleGroup(viewGroup);
        recentToggle.setToggleGroup(viewGroup);
        viewGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                viewGroup.selectToggle(oldToggle);
                return;
            }
            showingRecent = newToggle == recentToggle;
            profileSearchField.clear();
            profileSearchField.setPromptText(showingRecent ? "Search recent..." : "Search profiles...");
            setListItems(showingRecent ? filteredHistory : filteredProfiles);
            newProfileButton.setText(showingRecent ? "Reconnect" : "New");
            duplicateProfileButton.setText(showingRecent ? "Remove" : "Duplicate");
            deleteProfileButton.setText(showingRecent ? "Clear All" : "Delete");
            refreshButtonStates();
        });
    }

    private void setupAuthMethodToggle() {
        ToggleGroup authGroup = new ToggleGroup();
        keyAuthToggle.setToggleGroup(authGroup);
        passwordAuthToggle.setToggleGroup(authGroup);
        authGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                authGroup.selectToggle(oldToggle);
                return;
            }
            boolean usingPassword = newToggle == passwordAuthToggle;
            keyAuthRow.setVisible(!usingPassword);
            keyAuthRow.setManaged(!usingPassword);
            passwordAuthRow.setVisible(usingPassword);
            passwordAuthRow.setManaged(usingPassword);
            updateCommandPreview();
        });
    }

    private AuthMethod selectedAuthMethod() {
        return passwordAuthToggle.isSelected() ? AuthMethod.PASSWORD : AuthMethod.PRIVATE_KEY;
    }

    private void applyAuthMethodToForm(AuthMethod authMethod) {
        boolean usingPassword = authMethod == AuthMethod.PASSWORD;
        passwordAuthToggle.setSelected(usingPassword);
        keyAuthToggle.setSelected(!usingPassword);
        keyAuthRow.setVisible(!usingPassword);
        keyAuthRow.setManaged(!usingPassword);
        passwordAuthRow.setVisible(usingPassword);
        passwordAuthRow.setManaged(usingPassword);
    }

    @SuppressWarnings("unchecked")
    private void setListItems(ObservableList<?> items) {
        profileListView.setItems((ObservableList<Object>) items);
    }

    private void setupProfileList() {
        filteredProfiles = new FilteredList<>(profiles, p -> true);
        filteredHistory = new FilteredList<>(historyEntries, h -> true);
        setListItems(filteredProfiles);

        profileListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                if (item instanceof ConnectionProfile profile) {
                    setGraphic(buildProfileRow(profile));
                } else if (item instanceof ConnectionHistoryEntry entry) {
                    setGraphic(buildHistoryRow(entry));
                }
                setText(null);
            }
        });

        profileSearchField.textProperty().addListener((obs, oldVal, text) -> {
            String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            filteredProfiles.setPredicate(p -> needle.isEmpty()
                    || p.displayName().toLowerCase(Locale.ROOT).contains(needle)
                    || p.getHost().toLowerCase(Locale.ROOT).contains(needle)
                    || p.getUsername().toLowerCase(Locale.ROOT).contains(needle));
            filteredHistory.setPredicate(h -> needle.isEmpty()
                    || h.displayLabel().toLowerCase(Locale.ROOT).contains(needle)
                    || h.host().toLowerCase(Locale.ROOT).contains(needle)
                    || h.username().toLowerCase(Locale.ROOT).contains(needle));
        });

        profileListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            refreshButtonStates();
            if (suppressSelectionEvents || newVal == null) {
                return;
            }
            if (newVal instanceof ConnectionProfile profile) {
                loadProfileIntoForm(profile);
            } else if (newVal instanceof ConnectionHistoryEntry entry) {
                loadHistoryIntoForm(entry);
            }
        });

        profiles.addListener((javafx.collections.ListChangeListener<ConnectionProfile>) c -> refreshButtonStates());
        historyEntries.addListener((javafx.collections.ListChangeListener<ConnectionHistoryEntry>) c -> refreshButtonStates());
    }

    private void refreshButtonStates() {
        Object selected = profileListView.getSelectionModel().getSelectedItem();
        if (showingRecent) {
            newProfileButton.setDisable(selected == null);
            duplicateProfileButton.setDisable(selected == null);
            deleteProfileButton.setDisable(historyEntries.isEmpty());
        } else {
            newProfileButton.setDisable(false);
            duplicateProfileButton.setDisable(selected == null);
            deleteProfileButton.setDisable(selected == null);
        }
    }

    private HBox buildProfileRow(ConnectionProfile profile) {
        Circle dot = new Circle(5, Color.web(
                profile.getColorTag() == null || profile.getColorTag().isBlank()
                        ? ConnectionProfile.DEFAULT_COLOR : profile.getColorTag()));
        Label nameLabel = new Label(profile.displayName());
        nameLabel.getStyleClass().add("profile-cell-name");
        HBox row = new HBox(8, dot, nameLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        if (profile.isProduction()) {
            Label prodBadge = new Label("PROD");
            prodBadge.getStyleClass().add("profile-prod-badge");
            row.getChildren().add(prodBadge);
        }
        return row;
    }

    private HBox buildHistoryRow(ConnectionHistoryEntry entry) {
        Label nameLabel = new Label(entry.profileName() != null ? entry.profileName() : entry.displayLabel());
        nameLabel.getStyleClass().add("profile-cell-name");
        Label timeLabel = new Label(entry.timeAgo());
        timeLabel.getStyleClass().add("history-time-label");
        HBox row = new HBox(8, nameLabel, timeLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void loadProfileIntoForm(ConnectionProfile profile) {
        editingProfileId = profile.getId();
        nameField.setText(profile.getName());
        hostField.setText(profile.getHost());
        portField.setText(String.valueOf(profile.getPort()));
        usernameField.setText(profile.getUsername());
        applyAuthMethodToForm(profile.getAuthMethod());
        keyPathField.setText(profile.getPrivateKeyPath());
        passphraseField.clear();
        loginPasswordField.clear();
        setSelectedColorTag(profile.getColorTag());
        productionCheck.setSelected(profile.isProduction());
        updateCommandPreview();
    }

    private void loadHistoryIntoForm(ConnectionHistoryEntry entry) {
        editingProfileId = null;
        nameField.setText(entry.profileName() != null ? entry.profileName() : "");
        hostField.setText(entry.host());
        portField.setText(String.valueOf(entry.port()));
        usernameField.setText(entry.username());
        applyAuthMethodToForm(entry.authMethod());
        keyPathField.setText(entry.privateKeyPath());
        passphraseField.clear();
        loginPasswordField.clear();
        setSelectedColorTag(ConnectionProfile.DEFAULT_COLOR);
        productionCheck.setSelected(false);
        updateCommandPreview();
    }

    @FXML
    private void onPrimaryAction() {
        if (showingRecent) {
            reconnectSelectedHistory();
        } else {
            onNewProfile();
        }
    }

    @FXML
    private void onSecondaryAction() {
        if (showingRecent) {
            removeSelectedHistory();
        } else {
            onDuplicateProfile();
        }
    }

    @FXML
    private void onTertiaryAction() {
        if (showingRecent) {
            clearAllHistory();
        } else {
            onDeleteProfile();
        }
    }

    private void reconnectSelectedHistory() {
        Object selected = profileListView.getSelectionModel().getSelectedItem();
        if (!(selected instanceof ConnectionHistoryEntry entry)) {
            return;
        }
        loadHistoryIntoForm(entry);
        onTestConnection();
    }

    private void removeSelectedHistory() {
        Object selected = profileListView.getSelectionModel().getSelectedItem();
        if (!(selected instanceof ConnectionHistoryEntry entry)) {
            return;
        }
        historyStore.remove(entry);
        historyEntries.remove(entry);
    }

    private void clearAllHistory() {
        if (historyEntries.isEmpty()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Clear all connection history? This cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm);
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.YES) {
                historyStore.clearAll();
                historyEntries.clear();
            }
        });
    }

    private void onNewProfile() {
        suppressSelectionEvents = true;
        profileListView.getSelectionModel().clearSelection();
        suppressSelectionEvents = false;

        editingProfileId = null;
        nameField.clear();
        hostField.clear();
        portField.setText("22");
        usernameField.clear();
        applyAuthMethodToForm(AuthMethod.PRIVATE_KEY);
        keyPathField.clear();
        passphraseField.clear();
        loginPasswordField.clear();
        setSelectedColorTag(ConnectionProfile.DEFAULT_COLOR);
        productionCheck.setSelected(false);
        updateCommandPreview();
        nameField.requestFocus();
    }

    private void onDuplicateProfile() {
        ConnectionProfile base = currentProfile();
        String copyName = base.getName().isBlank() ? "Copy" : base.getName() + " (copy)";
        ConnectionProfile copy = base.copyAsNew(copyName);
        try {
            profileStore.save(copy);
            profiles.add(copy);
            suppressSelectionEvents = true;
            profileListView.getSelectionModel().select(copy);
            suppressSelectionEvents = false;
            loadProfileIntoForm(copy);
            showStatus("Duplicated as \"" + copy.displayName() + "\".", false);
        } catch (Exception e) {
            showStatus("Could not duplicate profile: " + e.getMessage(), true);
        }
    }

    private void onDeleteProfile() {
        Object selectedObj = profileListView.getSelectionModel().getSelectedItem();
        if (!(selectedObj instanceof ConnectionProfile selected)) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete profile \"" + selected.displayName() + "\"? This cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm);
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button != ButtonType.YES) {
                return;
            }
            profileStore.delete(selected.getId());
            profiles.remove(selected);
            if (selected.getId().equals(editingProfileId)) {
                onNewProfile();
            }
            showStatus("Profile deleted.", false);
        });
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
        ConnectionProfile profile = currentProfile();
        try {
            profileStore.save(profile);
            int existingIndex = indexOfProfileById(profile.getId());
            if (existingIndex >= 0) {
                profiles.set(existingIndex, profile);
            } else {
                profiles.add(profile);
            }
            editingProfileId = profile.getId();
            suppressSelectionEvents = true;
            if (!showingRecent) {
                profileListView.getSelectionModel().select(profile);
            }
            suppressSelectionEvents = false;
            showStatus("Profile saved.", false);
        } catch (Exception e) {
            showStatus("Could not save profile: " + e.getMessage(), true);
        }
    }

    private int indexOfProfileById(String id) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    @FXML
    private void onTestConnection() {
        ConnectionProfile profile = currentProfile();
        String passphrase = passphraseField.getText();
        String password = loginPasswordField.getText();

        testConnectionButton.setDisable(true);
        busyIndicator.setVisible(true);
        busyIndicator.setManaged(true);
        showStatus("Connecting...", false);

        Thread worker = new Thread(() -> attemptConnect(profile, passphrase, password), "ssh-connect");
        worker.setDaemon(true);
        worker.start();
    }

    private void attemptConnect(ConnectionProfile profile, String passphrase, String password) {
        SshSessionManager sessionManager = new SshSessionManager();
        try {
            sessionManager.connect(profile.getHost(), profile.getPort(), profile.getUsername(),
                    profile.getPrivateKeyPath(), passphrase, password, createHostKeyPrompt());
            String rootPath = sessionManager.resolveHomeDirectory();
            profileStore.setLastUsedId(profile.getId());
            historyStore.record(new ConnectionHistoryEntry(profile.getHost(), profile.getPort(), profile.getUsername(),
                    profile.getAuthMethod(), profile.getPrivateKeyPath(),
                    profile.getName() == null || profile.getName().isBlank() ? null : profile.getName(),
                    System.currentTimeMillis()));
            auditLogStore.record(profile.getHost(), profile.getUsername(), "Connect", "success", profile.getName());

            Platform.runLater(() -> {
                try {
                    DesktopController controller = App.loadScene("/com/linuxdesk/desktop.fxml", 1024, 680);
                    controller.init(sessionManager, profile, rootPath);
                } catch (Exception e) {
                    busyIndicator.setVisible(false);
                    busyIndicator.setManaged(false);
                    showStatus("Connected, but failed to open desktop: " + e.getMessage(), true);
                    testConnectionButton.setDisable(false);
                    sessionManager.close();
                }
            });
        } catch (Exception e) {
            auditLogStore.record(profile.getHost(), profile.getUsername(), "Connect", "failure", e.getMessage());
            sessionManager.close();
            Platform.runLater(() -> {
                busyIndicator.setVisible(false);
                busyIndicator.setManaged(false);
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
            ThemeManager.apply(alert);
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
            ThemeManager.apply(alert);

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
    private boolean awaitFxResult(Supplier<Boolean> onFxThread) {
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
        if (editingProfileId != null && !editingProfileId.isBlank()) {
            profile.setId(editingProfileId);
        }
        profile.setName(nameField.getText().trim());
        profile.setHost(hostField.getText().trim());
        profile.setPort(parsePort(portField.getText()));
        profile.setUsername(usernameField.getText().trim());
        profile.setAuthMethod(selectedAuthMethod());
        profile.setPrivateKeyPath(keyPathField.getText().trim());
        profile.setColorTag(selectedColor);
        profile.setProduction(productionCheck.isSelected());
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
