package com.linuxdesk.ui;

import com.linuxdesk.ssh.RemoteProcess;
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
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import java.util.List;
import java.util.Locale;

public class TaskManagerController {

    private static final long REFRESH_INTERVAL_MS = 2000;

    @FXML private TableView<RemoteProcess> processTable;
    @FXML private TableColumn<RemoteProcess, String> nameColumn;
    @FXML private TableColumn<RemoteProcess, Number> pidColumn;
    @FXML private TableColumn<RemoteProcess, String> userColumn;
    @FXML private TableColumn<RemoteProcess, String> statusColumn;
    @FXML private TableColumn<RemoteProcess, Number> cpuColumn;
    @FXML private TableColumn<RemoteProcess, Number> memColumn;
    @FXML private TextField searchField;
    @FXML private Label statusLabel;
    @FXML private Button endTaskButton;

    private SshSessionManager sessionManager;
    private final ObservableList<RemoteProcess> allProcesses = FXCollections.observableArrayList();
    private volatile boolean running = true;

    public void init(SshSessionManager sessionManager, Stage stage) {
        this.sessionManager = sessionManager;
        stage.setTitle("Task Manager");
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> running = false);

        nameColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().command()));
        pidColumn.setCellValueFactory(data -> new SimpleIntegerProperty(data.getValue().pid()));
        userColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().user()));
        statusColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status()));
        cpuColumn.setCellValueFactory(data -> new SimpleDoubleProperty(data.getValue().cpuPercent()));
        cpuColumn.setCellFactory(col -> percentCell());
        memColumn.setCellValueFactory(data -> new SimpleLongProperty(data.getValue().memoryKb()));
        memColumn.setCellFactory(col -> memoryCell());

        FilteredList<RemoteProcess> filtered = new FilteredList<>(allProcesses, p -> true);
        searchField.textProperty().addListener((obs, old, text) -> filtered.setPredicate(p -> matches(p, text)));

        SortedList<RemoteProcess> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(processTable.comparatorProperty());
        processTable.setItems(sorted);
        cpuColumn.setSortType(TableColumn.SortType.DESCENDING);
        processTable.getSortOrder().add(cpuColumn);

        processTable.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> endTaskButton.setDisable(selected == null));

        startPolling();
    }

    private static boolean matches(RemoteProcess process, String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String needle = text.trim().toLowerCase(Locale.ROOT);
        return process.command().toLowerCase(Locale.ROOT).contains(needle)
                || String.valueOf(process.pid()).contains(needle);
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
        loadProcesses();
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
                if (running) {
                    refreshQuietly();
                }
            }
        }, "task-manager-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private void loadProcesses() {
        statusLabel.setText("Loading...");
        Thread worker = new Thread(this::refreshQuietly, "task-manager-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshQuietly() {
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
}
