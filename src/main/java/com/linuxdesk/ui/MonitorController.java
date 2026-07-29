package com.linuxdesk.ui;

import com.linuxdesk.ssh.CpuTimes;
import com.linuxdesk.ssh.DiskUsage;
import com.linuxdesk.ssh.SshSessionManager;
import com.linuxdesk.ssh.SystemSnapshot;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

public class MonitorController {

    private static final long POLL_INTERVAL_MS = 2000;
    private static final int MAX_SAMPLES = 60;

    @FXML private Label cpuValueLabel;
    @FXML private Label memoryValueLabel;
    @FXML private Label statusLabel;
    @FXML private LineChart<Number, Number> cpuChart;
    @FXML private LineChart<Number, Number> memoryChart;
    @FXML private TableView<DiskUsage> diskTable;
    @FXML private TableColumn<DiskUsage, String> filesystemColumn;
    @FXML private TableColumn<DiskUsage, String> sizeColumn;
    @FXML private TableColumn<DiskUsage, String> usedColumn;
    @FXML private TableColumn<DiskUsage, String> availColumn;
    @FXML private TableColumn<DiskUsage, String> usePercentColumn;
    @FXML private TableColumn<DiskUsage, String> mountedOnColumn;

    private SshSessionManager sessionManager;
    private volatile boolean running = true;
    private CpuTimes previousCpuTimes;
    private int sampleIndex = 0;

    private final XYChart.Series<Number, Number> cpuSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> memorySeries = new XYChart.Series<>();

    public void init(SshSessionManager sessionManager, Stage stage) {
        this.sessionManager = sessionManager;
        stage.setTitle("Monitor");
        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> running = false);

        cpuChart.getData().add(cpuSeries);
        memoryChart.getData().add(memorySeries);

        filesystemColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().filesystem()));
        sizeColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().size()));
        usedColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().used()));
        availColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().avail()));
        usePercentColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().usePercent()));
        mountedOnColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().mountedOn()));

        startPolling();
    }

    private void startPolling() {
        Thread poller = new Thread(() -> {
            while (running) {
                pollOnce();
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "monitor-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private void pollOnce() {
        try {
            SystemSnapshot snapshot = sessionManager.sampleSystem();
            Platform.runLater(() -> applySnapshot(snapshot));
        } catch (Exception e) {
            Platform.runLater(() -> statusLabel.setText("Failed to sample: " + e.getMessage()));
        }
    }

    private void applySnapshot(SystemSnapshot snapshot) {
        int index = sampleIndex++;

        if (previousCpuTimes != null) {
            double cpuPercent = computeCpuPercent(previousCpuTimes, snapshot.cpuTimes());
            cpuValueLabel.setText(String.format("%.1f%%", cpuPercent));
            addSample(cpuSeries, index, cpuPercent);
        }
        previousCpuTimes = snapshot.cpuTimes();

        long usedBytes = snapshot.memory().totalBytes() - snapshot.memory().availableBytes();
        double memPercent = snapshot.memory().totalBytes() == 0 ? 0
                : 100.0 * usedBytes / snapshot.memory().totalBytes();
        memoryValueLabel.setText(String.format("%.1f GB / %.1f GB (%.0f%%)",
                usedBytes / 1_073_741_824.0,
                snapshot.memory().totalBytes() / 1_073_741_824.0,
                memPercent));
        addSample(memorySeries, index, memPercent);

        diskTable.getItems().setAll(snapshot.disks());
        statusLabel.setText("Updated");
    }

    private static void addSample(XYChart.Series<Number, Number> series, int index, double value) {
        series.getData().add(new XYChart.Data<>(index, value));
        while (series.getData().size() > MAX_SAMPLES) {
            series.getData().remove(0);
        }
    }

    private static double computeCpuPercent(CpuTimes previous, CpuTimes current) {
        long totalDelta = current.total() - previous.total();
        long idleDelta = current.idleAll() - previous.idleAll();
        if (totalDelta <= 0) {
            return 0;
        }
        return 100.0 * (totalDelta - idleDelta) / totalDelta;
    }
}
