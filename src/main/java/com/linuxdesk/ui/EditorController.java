package com.linuxdesk.ui;

import com.linuxdesk.ssh.RemoteEntry;
import com.linuxdesk.ssh.SshSessionManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.time.Duration;
import java.util.Locale;

public class EditorController {

    private static final KeyCombination FIND_SHORTCUT = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);

    @FXML private Label pathLabel;
    @FXML private Label statusLabel;
    @FXML private Button saveButton;
    @FXML private StackPane editorContainer;
    @FXML private HBox findBar;
    @FXML private TextField findField;
    @FXML private TextField replaceField;
    @FXML private Label matchCountLabel;
    @FXML private CheckBox matchCaseCheck;

    private SshSessionManager sessionManager;
    private String path;
    private Stage stage;
    private String savedContent;
    private CodeArea codeArea;

    public void init(SshSessionManager sessionManager, RemoteEntry entry, String content, Stage stage) {
        this.sessionManager = sessionManager;
        this.path = entry.path();
        this.stage = stage;
        this.savedContent = content;
        pathLabel.setText(entry.path());

        SyntaxHighlighter highlighter = new SyntaxHighlighter(entry.name());
        codeArea = new CodeArea();
        codeArea.getStyleClass().add("code-editor");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.replaceText(content);
        codeArea.setStyleSpans(0, highlighter.highlight(content));

        codeArea.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignored -> codeArea.setStyleSpans(0, highlighter.highlight(codeArea.getText())));

        editorContainer.getChildren().add(new VirtualizedScrollPane<>(codeArea));

        codeArea.setOnKeyPressed(event -> {
            if (FIND_SHORTCUT.match(event)) {
                openFindBar();
                event.consume();
            }
        });

        findField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                closeFindBar();
            } else if (event.getCode() == KeyCode.ENTER) {
                findNext();
            }
        });
        findField.textProperty().addListener((obs, old, text) -> updateMatchCount());
        matchCaseCheck.selectedProperty().addListener((obs, old, val) -> updateMatchCount());

        stage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (codeArea.getText().equals(savedContent)) {
                return;
            }
            event.consume();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "You have unsaved changes to " + path + ". Discard them?",
                    ButtonType.YES, ButtonType.NO);
            ThemeManager.apply(confirm.getDialogPane());
            confirm.setHeaderText(null);
            confirm.showAndWait().ifPresent(button -> {
                if (button == ButtonType.YES) {
                    stage.close();
                }
            });
        });
    }

    @FXML
    private void onSave() {
        String content = codeArea.getText();
        saveButton.setDisable(true);
        statusLabel.setText("Saving...");

        Thread worker = new Thread(() -> {
            try {
                sessionManager.writeFile(path, content);
                Platform.runLater(() -> {
                    savedContent = content;
                    statusLabel.setText("Saved.");
                    saveButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Save failed: " + e.getMessage());
                    saveButton.setDisable(false);
                });
            }
        }, "sftp-write");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    private void onClose() {
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private void openFindBar() {
        findBar.setVisible(true);
        findBar.setManaged(true);
        findField.requestFocus();
        findField.selectAll();
        updateMatchCount();
    }

    @FXML
    private void onCloseFindBar() {
        closeFindBar();
    }

    private void closeFindBar() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        codeArea.requestFocus();
    }

    @FXML
    private void onFindNext() {
        findNext();
    }

    @FXML
    private void onFindPrevious() {
        findPrevious();
    }

    private void findNext() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        String haystack = normalize(codeArea.getText());
        String query = normalize(needle);
        int from = codeArea.getSelection().getEnd();
        int index = haystack.indexOf(query, from);
        if (index < 0) {
            index = haystack.indexOf(query);
        }
        if (index >= 0) {
            selectMatch(index, query.length());
        }
        updateMatchCount();
    }

    private void findPrevious() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        String haystack = normalize(codeArea.getText());
        String query = normalize(needle);
        int from = codeArea.getSelection().getStart() - 1;
        int index = from >= 0 ? haystack.lastIndexOf(query, from) : -1;
        if (index < 0) {
            index = haystack.lastIndexOf(query);
        }
        if (index >= 0) {
            selectMatch(index, query.length());
        }
        updateMatchCount();
    }

    private void selectMatch(int start, int length) {
        codeArea.selectRange(start, start + length);
        codeArea.requestFollowCaret();
    }

    @FXML
    private void onReplace() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        IndexRange selection = codeArea.getSelection();
        if (selection.getLength() > 0) {
            String selectedText = codeArea.getText(selection.getStart(), selection.getEnd());
            boolean matches = matchCaseCheck.isSelected()
                    ? selectedText.equals(needle)
                    : selectedText.equalsIgnoreCase(needle);
            if (matches) {
                codeArea.replaceText(selection.getStart(), selection.getEnd(), replaceField.getText());
            }
        }
        findNext();
    }

    @FXML
    private void onReplaceAll() {
        String needle = findField.getText();
        if (needle.isEmpty()) {
            return;
        }
        String content = codeArea.getText();
        String replacement = replaceField.getText();
        int count = countOccurrences(normalize(content), normalize(needle));
        String result = matchCaseCheck.isSelected()
                ? content.replace(needle, replacement)
                : replaceAllIgnoreCase(content, needle, replacement);
        codeArea.replaceText(result);
        statusLabel.setText("Replaced " + count + " occurrence" + (count == 1 ? "" : "s") + ".");
        updateMatchCount();
    }

    private void updateMatchCount() {
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            matchCountLabel.setText("");
            return;
        }
        int count = countOccurrences(normalize(codeArea.getText()), normalize(needle));
        matchCountLabel.setText(count + " match" + (count == 1 ? "" : "es"));
    }

    private String normalize(String text) {
        return matchCaseCheck.isSelected() ? text : text.toLowerCase(Locale.ROOT);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String replaceAllIgnoreCase(String haystack, String needle, String replacement) {
        String lowerHaystack = haystack.toLowerCase(Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int from = 0;
        int index;
        while ((index = lowerHaystack.indexOf(lowerNeedle, from)) != -1) {
            sb.append(haystack, from, index).append(replacement);
            from = index + needle.length();
        }
        sb.append(haystack.substring(from));
        return sb.toString();
    }
}
