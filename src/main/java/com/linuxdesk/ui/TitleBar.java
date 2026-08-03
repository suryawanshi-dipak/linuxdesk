package com.linuxdesk.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

/**
 * Replaces the native Windows title bar (undecorated stage) with a themed one, since the OS
 * chrome is always light and clashes with the dark UI. Also hosts the light/dark toggle.
 */
public class TitleBar extends HBox {

    public TitleBar(Stage stage, Scene scene) {
        this(stage, scene, "LinuxDesk");
    }

    public TitleBar(Stage stage, Scene scene, String titleText) {
        getStyleClass().add("title-bar");
        setPrefHeight(38);

        Label icon = new Label("⌘");
        icon.getStyleClass().add("title-bar-icon");

        Label title = new Label(titleText);
        title.getStyleClass().add("title-bar-label");

        HBox dragArea = new HBox(8, icon, title);
        dragArea.setAlignment(Pos.CENTER_LEFT);
        dragArea.setPadding(new Insets(0, 0, 0, 14));
        HBox.setHgrow(dragArea, Priority.ALWAYS);
        enableDragging(stage, dragArea);
        dragArea.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });

        Button themeToggleButton = new Button(themeGlyph());
        themeToggleButton.getStyleClass().add("title-bar-theme-btn");
        themeToggleButton.setOnAction(event -> {
            ThemeManager.toggle(scene);
            themeToggleButton.setText(themeGlyph());
        });

        Button minimizeButton = windowButton("─", "title-bar-btn");
        minimizeButton.setOnAction(event -> stage.setIconified(true));

        Button maximizeButton = windowButton("□", "title-bar-btn");
        maximizeButton.setOnAction(event -> stage.setMaximized(!stage.isMaximized()));

        Button closeButton = windowButton("✕", "title-bar-btn-close");
        closeButton.setOnAction(event -> stage.close());

        getChildren().addAll(dragArea, themeToggleButton, minimizeButton, maximizeButton, closeButton);
    }

    private String themeGlyph() {
        return ThemeManager.current() == ThemeManager.Theme.DARK ? "☀" : "☽";
    }

    private Button windowButton(String symbol, String styleClass) {
        Button button = new Button(symbol);
        button.getStyleClass().add(styleClass);
        return button;
    }

    private void enableDragging(Stage stage, HBox dragArea) {
        double[] dragDelta = new double[2];
        dragArea.setOnMousePressed(event -> {
            dragDelta[0] = stage.getX() - event.getScreenX();
            dragDelta[1] = stage.getY() - event.getScreenY();
        });
        dragArea.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() + dragDelta[0]);
            stage.setY(event.getScreenY() + dragDelta[1]);
        });
    }
}
