package com.linuxdesk.ui;

import com.linuxdesk.ssh.RemoteProcess;
import com.linuxdesk.ssh.RemoteService;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TaskManagerController {

    private enum View { PROCESSES, SERVICES }

    private static final long REFRESH_INTERVAL_MS = 2000;
    private static final Set<String> CRITICAL_UNITS = Set.of("ssh.service", "sshd.service");

    @FXML private ToggleButton processesToggle;
    @FXML private ToggleButton servicesToggle;
    @FXML private TextField searchField;
    @FXML private Label statusLabel;
    @FXML private Button endTaskButton;
    @FXML private Button serviceStartButton;
    @FXML private Button serviceStopButton;
    @FXML private Button serviceRestartButton;
    @FXML private Button serviceEnableButton;
    @FXML private Button serviceDisableButton;

    @FXML private TableView<RemoteProcess> processTable;
    @FXML private TableColumn<RemoteProcess, String> nameColumn;
    @FXML private TableColumn<RemoteProcess, Number> pidColumn;
    @FXML private TableColumn<RemoteProcess, String> userColumn;
    @FXML private TableColumn<RemoteProcess, String> statusColumn;
    @FXML private TableColumn<RemoteProcess, Number> cpuColumn;
    @FXML private TableColumn<RemoteProcess, Number> memColumn;

    @FXML private TableView<RemoteService> serviceTable;
    @FXML private TableColumn<RemoteService, String> serviceNameColumn;
    @FXML private TableColumn<RemoteService, String> activeColumn;
    @FXML private TableColumn<RemoteService, String> subColumn;
    @FXML private TableColumn<RemoteService, String> enabledColumn;
    @FXML private TableColumn<RemoteService, String> descriptionColumn;

    private SshSessionManager sessionManager;
    private final ObservableList<RemoteProcess> allProcesses = FXCollections.observableArrayList();
    private final ObservableList<RemoteService> allServices = FXCollections.observableArrayList();
    private FilteredList<RemoteProcess> filteredProcesses;
    private FilteredList<RemoteService> filteredServices;
    private View currentView = View.PROCESSES;
    private volatile boolean running = true;
    private boolean servicesLoadedOnce = false;

    public void init(SshSessionManager sessionManager, Stage stage) {
        this.sessionManager = sessionManager;
        stage.setTitle("Task Manager");
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> running = false);

        setupProcessTable();
        setupServiceTable();

        processesToggle.setOnAction(e -> switchView(View.PROCESSES));
        servicesToggle.setOnAction(e -> switchView(View.SERVICES));

        switchView(View.PROCESSES);
        startPolling();
    }

    private void setupProcessTable() {
        nameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().command()));
        pidColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().pid()));
        userColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().user()));
        statusColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        cpuColumn.setCellValueFactory(data -> new SimpleDoubleProperty(data.getValue().cpuPercent()));
        cpuColumn.setCellFactory(col -> percentCell());
        memColumn.setCellValueFactory(data -> new SimpleLongProperty(data.getValue().memoryKb()));
        memColumn.setCellFactory(col -> memoryCell());

        filteredProcesses = new FilteredList<>(allProcesses, p -> true);
        searchField.textProperty().addListener((obs, old, text) -> {
            if (currentView == View.PROCESSES) {
                filteredProcesses.setPredicate(p -> matchesProcess(p, text));
            }
        });

        SortedList<RemoteProcess> sorted = new SortedList<>(filteredProcesses);
        sorted.comparatorProperty().bind(processTable.comparatorProperty());
        processTable.setItems(sorted);
        cpuColumn.setSortType(TableColumn.SortType.DESCENDING);
        processTable.getSortOrder().add(cpuColumn);

        processTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> endTaskButton.setDisable(selected == null));
    }

    private void setupServiceTable() {
        serviceNameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().name()));
        activeColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().active()));
        subColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().sub()));
        enabledColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().enabled()));
        descriptionColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().description()));

        filteredServices = new FilteredList<>(allServices, s -> true);
        searchField.textProperty().addListener((obs, old, text) -> {
            if (currentView == View.SERVICES) {
                filteredServices.setPredicate(s -> matchesService(s, text));
            }
        });

        SortedList<RemoteService> sorted = new SortedList<>(filteredServices);
        sorted.comparatorProperty().bind(serviceTable.comparatorProperty());
        serviceTable.setItems(sorted);
        serviceNameColumn.setSortType(TableColumn.SortType.ASCENDING);
        serviceTable.getSortOrder().add(serviceNameColumn);

        serviceTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> setServiceButtonsDisabled(selected == null));
        setServiceButtonsDisabled(true);
    }

    private void switchView(View view) {
        currentView = view;
        boolean isProcesses = view == View.PROCESSES;

        processTable.setVisible(isProcesses);
        processTable.setManaged(isProcesses);
        serviceTable.setVisible(!isProcesses);
        serviceTable.setManaged(!isProcesses);

        endTaskButton.setVisible(isProcesses);
        endTaskButton.setManaged(isProcesses);
        serviceStartButton.setVisible(!isProcesses);
        serviceStartButton.setManaged(!isProcesses);
        serviceStopButton.setVisible(!isProcesses);
        serviceStopButton.setManaged(!isProcesses);
        serviceRestartButton.setVisible(!isProcesses);
        serviceRestartButton.setManaged(!isProcesses);
        serviceEnableButton.setVisible(!isProcesses);
        serviceEnableButton.setManaged(!isProcesses);
        serviceDisableButton.setVisible(!isProcesses);
        serviceDisableButton.setManaged(!isProcesses);

        processesToggle.setSelected(isProcesses);
        servicesToggle.setSelected(!isProcesses);

        String text = searchField.getText();
        if (isProcesses) {
            filteredProcesses.setPredicate(p -> matchesProcess(p, text));
        } else {
            filteredServices.setPredicate(s -> matchesService(s, text));
            if (!servicesLoadedOnce) {
                servicesLoadedOnce = true;
                loadServices();
            }
        }
    }

    private static boolean matchesProcess(RemoteProcess process, String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String needle = text.trim().toLowerCase(Locale.ROOT);
        return process.command().toLowerCase(Locale.ROOT).contains(needle)
                || String.valueOf(process.pid()).contains(needle);
    }

    private static boolean matchesService(RemoteService service, String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String needle = text.trim().toLowerCase(Locale.ROOT);
        return service.name().toLowerCase(Locale.ROOT).contains(needle)
                || service.description().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static TableCell<RemoteProcess, Number> percentCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : String.format("%.1f%%", value.doubleValue()));
            }
        };
    }

    private static TableCell<RemoteProcess, Number> memoryCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(Number value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : String.format("%.1f MB", value.longValue() / 1024.0));
            }
        };
    }

    @FXML
    private void onRefresh() {
        if (currentView == View.PROCESSES) {
            loadProcesses();
        } else {
            loadServices();
        }
    }

    @FXML
    private void onEndTask() {
        RemoteProcess selected = processTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "End \"" + selected.command() + "\" (PID " + selected.pid() + ")? Unsaved work in that process will be lost.",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button != ButtonType.YES) {
                return;
            }
            statusLabel.setText("Ending " + selected.command() + "...");

            Thread worker = new Thread(() -> {
                try {
                    sessionManager.killProcess(selected.pid());
                    Platform.runLater(this::loadProcesses);
                } catch (Exception e) {
                    Platform.runLater(() -> statusLabel.setText("End task failed: " + e.getMessage()));
                }
            }, "task-manager-kill");
            worker.setDaemon(true);
            worker.start();
        });
    }

    @FXML
    private void onServiceStart() {
        runServiceAction("start");
    }

    @FXML
    private void onServiceStop() {
        runServiceAction("stop");
    }

    @FXML
    private void onServiceRestart() {
        runServiceAction("restart");
    }

    @FXML
    private void onServiceEnable() {
        runServiceAction("enable");
    }

    @FXML
    private void onServiceDisable() {
        runServiceAction("disable");
    }

    private void runServiceAction(String action) {
        RemoteService selected = serviceTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        boolean disruptive = !action.equals("start") && !action.equals("enable");
        if (disruptive && CRITICAL_UNITS.contains(selected.name())) {
            confirmCriticalServiceAction(selected, action);
        } else {
            confirmServiceAction(selected, action);
        }
    }

    private void confirmServiceAction(RemoteService service, String action) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                capitalize(action) + " \"" + service.name() + "\"?",
                ButtonType.YES, ButtonType.NO);
        ThemeManager.apply(confirm.getDialogPane());
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(button -> {
            if (button == ButtonType.YES) {
                performServiceAction(service, action);
            }
        });
    }

    private void confirmCriticalServiceAction(RemoteService service, String action) {
        TextInputDialog dialog = new TextInputDialog();
        ThemeManager.apply(dialog.getDialogPane());
        dialog.setHeaderText(null);
        dialog.setTitle("Confirm: SSH service");
        dialog.setContentText(capitalize(action) + "ing \"" + service.name()
                + "\" may disconnect this and every other SSH session to this host.\n"
                + "Type the service name to confirm:");
        dialog.showAndWait().ifPresent(typed -> {
            if (typed.trim().equals(service.name())) {
                performServiceAction(service, action);
            } else {
                statusLabel.setText("Confirmation text didn't match — action cancelled.");
            }
        });
    }

    private static String capitalize(String action) {
        return Character.toUpperCase(action.charAt(0)) + action.substring(1);
    }

    private void performServiceAction(RemoteService service, String action) {
        statusLabel.setText(capitalize(action) + "ing " + service.name() + "...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.controlService(service.name(), action);
                Platform.runLater(this::loadServices);
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText(capitalize(action) + " failed: " + e.getMessage()));
            }
        }, "service-" + action);
        worker.setDaemon(true);
        worker.start();
    }

    private void setServiceButtonsDisabled(boolean disabled) {
        serviceStartButton.setDisable(disabled);
        serviceStopButton.setDisable(disabled);
        serviceRestartButton.setDisable(disabled);
        serviceEnableButton.setDisable(disabled);
        serviceDisableButton.setDisable(disabled);
    }

    private void startPolling() {
        loadProcesses();
        Thread poller = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running && currentView == View.PROCESSES) {
                    refreshProcessesQuietly();
                }
            }
        }, "task-manager-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private void loadProcesses() {
        statusLabel.setText("Loading...");
        Thread worker = new Thread(this::refreshProcessesQuietly, "task-manager-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshProcessesQuietly() {
        try {
            List<RemoteProcess> processes = sessionManager.listProcesses();
            Platform.runLater(() -> {
                RemoteProcess selected = processTable.getSelectionModel().getSelectedItem();
                allProcesses.setAll(processes);
                if (selected != null) {
                    processes.stream()
                            .filter(p -> p.pid() == selected.pid())
                            .findFirst()
                            .ifPresent(p -> processTable.getSelectionModel().select(p));
                }
                statusLabel.setText(processes.size() + " processes");
            });
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText("Failed to load processes: " + e.getMessage()));
        }
    }

    private void loadServices() {
        statusLabel.setText("Loading...");

        Thread worker = new Thread(() -> {
            try {
                List<RemoteService> services = sessionManager.listServices();
                Platform.runLater(() -> {
                    RemoteService selected = serviceTable.getSelectionModel().getSelectedItem();
                    allServices.setAll(services);
                    if (selected != null) {
                        services.stream()
                                .filter(s -> s.name().equals(selected.name()))
                                .findFirst()
                                .ifPresent(s -> serviceTable.getSelectionModel().select(s));
                    }
                    statusLabel.setText(services.size() + " services");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to load services: " + e.getMessage()));
            }
        }, "service-list");
        worker.setDaemon(true);
        worker.start();
    }
}
