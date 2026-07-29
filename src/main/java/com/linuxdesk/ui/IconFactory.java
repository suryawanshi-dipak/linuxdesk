package com.linuxdesk.ui;

import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

/** Draws simple folder/file icons with JavaFX shapes so v1 doesn't need bundled image assets. */
final class IconFactory {

    private IconFactory() {
    }

    static StackPane createFolderIcon() {
        Rectangle tab = new Rectangle(22, 8);
        tab.setArcWidth(4);
        tab.setArcHeight(4);
        tab.setFill(Color.web("#e0b84c"));
        tab.setTranslateX(-13);
        tab.setTranslateY(-15);

        Rectangle body = new Rectangle(48, 34);
        body.setArcWidth(6);
        body.setArcHeight(6);
        body.setFill(Color.web("#f2c94c"));
        body.setStroke(Color.web("#c99a2e"));
        body.setStrokeWidth(1);
        body.setTranslateY(2);

        StackPane pane = new StackPane(body, tab);
        pane.setPrefSize(52, 48);
        return pane;
    }

    static StackPane createFileIcon() {
        Rectangle body = new Rectangle(38, 46);
        body.setArcWidth(4);
        body.setArcHeight(4);
        body.setFill(Color.web("#e8e8ec"));
        body.setStroke(Color.web("#9a9aa2"));
        body.setStrokeWidth(1);

        Polygon fold = new Polygon(0.0, 0.0, 12.0, 0.0, 12.0, 12.0);
        fold.setFill(Color.web("#c7c7ce"));
        fold.setStroke(Color.web("#9a9aa2"));
        fold.setTranslateX(13);
        fold.setTranslateY(-17);

        StackPane pane = new StackPane(body, fold);
        pane.setPrefSize(52, 52);
        return pane;
    }

    /** Smaller variants for compact contexts like search result rows. */
    static StackPane createSmallFolderIcon() {
        Rectangle tab = new Rectangle(9, 3);
        tab.setArcWidth(2);
        tab.setArcHeight(2);
        tab.setFill(Color.web("#e0b84c"));
        tab.setTranslateX(-5);
        tab.setTranslateY(-6);

        Rectangle body = new Rectangle(19, 13);
        body.setArcWidth(3);
        body.setArcHeight(3);
        body.setFill(Color.web("#f2c94c"));
        body.setStroke(Color.web("#c99a2e"));
        body.setStrokeWidth(0.75);
        body.setTranslateY(1);

        StackPane pane = new StackPane(body, tab);
        pane.setPrefSize(20, 18);
        return pane;
    }

    static StackPane createSmallFileIcon() {
        Rectangle body = new Rectangle(15, 18);
        body.setArcWidth(2);
        body.setArcHeight(2);
        body.setFill(Color.web("#e8e8ec"));
        body.setStroke(Color.web("#9a9aa2"));
        body.setStrokeWidth(0.75);

        Polygon fold = new Polygon(0.0, 0.0, 5.0, 0.0, 5.0, 5.0);
        fold.setFill(Color.web("#c7c7ce"));
        fold.setStroke(Color.web("#9a9aa2"));
        fold.setTranslateX(5);
        fold.setTranslateY(-6.5);

        StackPane pane = new StackPane(body, fold);
        pane.setPrefSize(20, 20);
        return pane;
    }
}
