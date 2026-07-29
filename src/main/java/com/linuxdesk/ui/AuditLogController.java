package com.linuxdesk.ui;

import com.linuxdesk.audit.AuditLogEntry;
import com.linuxdesk.audit.AuditLogStore;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.util.Comparator;
import java.util.Locale;

public class AuditLogController {

    @FXML private TextField searchField;
    @FXML private Label statusLabel;
    @FXML private TableView<AuditLogEntry> auditTable;
    @FXML private TableColumn<AuditLogEntry, String> timeColumn;
    @FXML private TableColumn<AuditLogEntry, String> hostColumn;
    @FXML private TableColumn<AuditLogEntry, String> userColumn;
    @FXML private TableColumn<AuditLogEntry, String> actionColumn;
    @FXML private TableColumn<AuditLogEntry, String> outcomeColumn;
    @FXML private TableColumn<AuditLogEntry, String> detailColumn;

    private final AuditLogStore auditLogStore = new AuditLogStore();
    private final ObservableList<AuditLogEntry> allEntries = FXCollections.observableArrayList();
    private FilteredList<AuditLogEntry> filteredEntries;

    public void init(Stage stage) {
        stage.setTitle("Audit Log");

        timeColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().formattedTime()));
        hostColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().host()));
        userColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().remoteUser()));
        actionColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().action()));
        outcomeColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().outcome()));
        detailColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().detail()));

        filteredEntries = new FilteredList<>(allEntries, e -> true);
        SortedList<AuditLogEntry> sorted = new SortedList<>(filteredEntries,
                Comparator.comparingLong(AuditLogEntry::timestamp).reversed());
        auditTable.setItems(sorted);

        searchField.textProperty().addListener((obs, old, text) -> {
            String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            filteredEntries.setPredicate(e -> needle.isEmpty()
                    || e.action().toLowerCase(Locale.ROOT).contains(needle)
                    || e.host().toLowerCase(Locale.ROOT).contains(needle)
                    || e.remoteUser().toLowerCase(Locale.ROOT).contains(needle)
                    || e.detail().toLowerCase(Locale.ROOT).contains(needle));
        });

        loadEntries();
    }

    private void loadEntries() {
        allEntries.setAll(auditLogStore.loadAll());
        statusLabel.setText(allEntries.size() + " entr" + (allEntries.size() == 1 ? "y" : "ies"));
    }

    @FXML
    private void onRefresh() {
        loadEntries();
    }

    @FXML
    private void onVerifyIntegrity() {
        boolean intact = auditLogStore.verifyChain();
        statusLabel.getStyleClass().removeAll("status-ok", "status-error");
        if (intact) {
            statusLabel.setText("Chain verified — no gaps or tampering detected.");
            statusLabel.getStyleClass().add("status-ok");
        } else {
            statusLabel.setText("TAMPERED — the hash chain is broken. Entries may have been edited or removed.");
            statusLabel.getStyleClass().add("status-error");
        }
    }
}
