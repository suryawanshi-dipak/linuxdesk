package com.linuxdesk;

import com.linuxdesk.ui.ThemeManager;
import com.linuxdesk.ui.TitleBar;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

public class App extends Application {

    private static final double TITLE_BAR_HEIGHT = 38;

    private static Stage primaryStage;

    @Override
    public void start(Stage stage) throws Exception {
        primaryStage = stage;
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("LinuxDesk");
        loadScene("/com/linuxdesk/login.fxml", 1040, 420);
        stage.show();
        stage.setMaximized(false);
        stage.setWidth(1040);
        stage.setHeight(420 + TITLE_BAR_HEIGHT);
        stage.centerOnScreen();
    }

    /** Loads an FXML view under the shared custom title bar and returns its controller so callers can inject data. */
    public static <T> T loadScene(String fxmlPath, double width, double height) throws IOException {
        FXMLLoader loader = new FXMLLoader(App.class.getResource(fxmlPath));
        Parent content = loader.load();

        BorderPane shell = new BorderPane();
        shell.setCenter(content);

        Scene scene = new Scene(shell, width, height + TITLE_BAR_HEIGHT);
        ThemeManager.apply(scene);

        shell.setTop(new TitleBar(primaryStage, scene));

        primaryStage.setScene(scene);
        primaryStage.centerOnScreen();
        return loader.getController();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
