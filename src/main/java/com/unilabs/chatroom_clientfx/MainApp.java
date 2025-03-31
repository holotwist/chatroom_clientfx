package com.unilabs.chatroom_clientfx;

import com.unilabs.chatroom_clientfx.controller.ChatController;
import com.unilabs.chatroom_clientfx.model.Chat;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

public class MainApp extends Application {

    private Chat model;

    @Override
    public void start(Stage primaryStage) {
        // Configuration (could be read from args or config file)
        String discovery = "https://raquelcloud.x10host.com/api/chatroom-19837/discovery";
        String relay = "https://raquelcloud.x10host.com/api/chatroom-19837/relay";

        // Get parameters if provided
        Parameters params = getParameters();
        if (!params.getRaw().isEmpty()) {
            discovery = params.getRaw().get(0);
        }
        if (params.getRaw().size() > 1) {
            relay = params.getRaw().get(1);
        }

        // Create the Model
        model = new Chat(discovery, relay);

        try {
            // Load FXML
            // Ensure the path to the FXML is correct relative to the resources root
            FXMLLoader loader = new FXMLLoader();
            URL fxmlUrl = getClass().getResource("ChatView.fxml");
            if (fxmlUrl == null) {
                System.err.println("Cannot load FXML file. Check the path.");
                Platform.exit();
                return;
            }
            loader.setLocation(fxmlUrl);
            BorderPane rootLayout = loader.load();

            // Give the controller access to the model
            ChatController controller = loader.getController();
            controller.setModel(model);

            // Setup Scene and Stage
            Scene scene = new Scene(rootLayout);
            primaryStage.setTitle("Distributed Chat Client (UUID: " + model.getClientUuid() +")");
            primaryStage.setScene(scene);

            // Handle window close request
            primaryStage.setOnCloseRequest(event -> {
                System.out.println("Window closing, initiating shutdown...");
                model.shutdown(); // Clean up resources
                Platform.exit(); // Exit JavaFX application
                System.exit(0); // Ensure JVM termination if background threads linger
            });

            primaryStage.show();

        } catch (IOException e) {
            System.err.println("Failed to load the FXML layout.");
            e.printStackTrace();
            Platform.exit();
        }
    }

    @Override
    public void stop() throws Exception {
        // This is called by JavaFX when the application exits (e.g., Platform.exit())
        // Ensure model shutdown is called if not handled by onCloseRequest
        System.out.println("Application stop() method called.");
        if (model != null) {
            model.shutdown();
        }
        super.stop();
    }


    public static void main(String[] args) {
        launch(args);
    }
}