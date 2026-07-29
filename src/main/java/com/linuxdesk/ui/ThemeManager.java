package com.linuxdesk.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;

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
