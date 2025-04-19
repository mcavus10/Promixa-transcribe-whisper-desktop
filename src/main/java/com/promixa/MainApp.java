package com.promixa;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.image.Image;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        URL fxmlUrl = getClass().getResource("main-view.fxml");
        if (fxmlUrl == null) {
            System.err.println("Cannot load FXML file: main-view.fxml");
            return;
        }
        Parent root = FXMLLoader.load(fxmlUrl);

        URL cssUrl = getClass().getResource("styles.css");
        if (cssUrl == null) {
            System.err.println("Cannot load CSS file: styles.css");
        } else {
            root.getStylesheets().add(cssUrl.toExternalForm());
        }

        // Set minimum window size to ensure all controls are visible
        Scene scene = new Scene(root); 
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(700);
        
        // Pass the HostServices to the scene properties for browser access
        scene.getProperties().put("hostServices", getHostServices());

        // Set app icon
        try {
            InputStream iconStream = getClass().getResourceAsStream("icons/app-icon.png");
            if (iconStream != null) {
                primaryStage.getIcons().add(new Image(iconStream));
            } else {
                System.err.println("Cannot load application icon");
            }
        } catch (Exception e) {
            System.err.println("Error loading application icon: " + e.getMessage());
        }

        primaryStage.setTitle("PROMIXA Transcription");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
