package com.linuxdesk.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.prefs.Preferences;

public final class ThemeManager {

    public enum Theme {
        DARK("/com/linuxdesk/dark-theme.css"),
        LIGHT("/com/linuxdesk/light-theme.css");

        private final String stylesheetPath;

        Theme(String stylesheetPath) {
            this.stylesheetPath = stylesheetPath;
        }
    }

    private static final String PREF_KEY = "theme";
    private static Theme current = loadSavedTheme();

    private ThemeManager() {
    }

    public static Theme current() {
        return current;
    }

    public static void apply(Scene scene) {
        scene.getStylesheets().setAll(stylesheetUrl());
    }

    public static void apply(Parent root) {
        root.getStylesheets().setAll(stylesheetUrl());
    }

    /**
     * Themes a Dialog/Alert AND replaces its native title bar with the app's own — the OS chrome is
     * always light and clashes with the dark UI, same reasoning as {@link TitleBar}. Also drops the
     * default alert-type graphic (question mark / warning triangle / etc.), which doesn't match the
     * app's flat look.
     */
    public static void apply(Dialog<?> dialog) {
        apply(dialog.getDialogPane());
        dialog.getDialogPane().setGraphic(null);
        dialog.initStyle(StageStyle.UNDECORATED);
        dialog.setOnShowing(event -> {
            Scene scene = dialog.getDialogPane().getScene();
            Stage stage = (Stage) scene.getWindow();
            Parent original = scene.getRoot();
            BorderPane shell = new BorderPane(original);
            scene.setRoot(shell);
            apply(scene);
            String title = dialog.getTitle() == null || dialog.getTitle().isBlank() ? "LinuxDesk" : dialog.getTitle();
            shell.setTop(new TitleBar(stage, scene, title));
        });
    }

    public static void toggle(Scene scene) {
        current = (current == Theme.DARK) ? Theme.LIGHT : Theme.DARK;
        prefs().put(PREF_KEY, current.name());
        apply(scene);
    }

    private static String stylesheetUrl() {
        return ThemeManager.class.getResource(current.stylesheetPath).toExternalForm();
    }

    private static Theme loadSavedTheme() {
        String saved = prefs().get(PREF_KEY, Theme.DARK.name());
        try {
            return Theme.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return Theme.DARK;
        }
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(ThemeManager.class);
    }
}
