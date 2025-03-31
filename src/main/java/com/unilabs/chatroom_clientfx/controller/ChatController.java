package com.unilabs.chatroom_clientfx.controller;

import com.unilabs.chatroom_clientfx.model.Chat;
import com.unilabs.chatroom_clientfx.model.records.ServerInfo;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.util.StringConverter;

public class ChatController {

    @FXML private ComboBox<ServerInfo> serverComboBox;
    @FXML private TextField nicknameField;
    @FXML private Button connectButton;
    @FXML private Button refreshButton;
    @FXML private Label statusLabel;
    @FXML private TextArea chatArea;
    @FXML private TextField messageInput;
    @FXML private Button sendButton;

    private Chat model;

    // Called after FXML fields are injected
    public void initialize() {
        // Configure ComboBox to display ServerInfo names
        serverComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(ServerInfo serverInfo) {
                return (serverInfo == null) ? null : serverInfo.toString(); // Use the record's toString
            }

            @Override
            public ServerInfo fromString(String string) {
                // Not needed for ComboBox selection if items are objects
                return null;
            }
        });

        // Disable send button initially
        sendButton.setDisable(true);
        messageInput.setDisable(true);
    }

    // Inject the model (called from MainApp)
    public void setModel(Chat model) {
        this.model = model;
        bindViewModel();
        model.fetchServerList(); // Initial fetch
    }

    private void bindViewModel() {
        // Bind ComboBox items to the model's server list
        // Use Bindings.bindContentBidirectional or listeners for robustness if needed,
        // but simple binding works for one-way display from model.
        Bindings.bindContent(serverComboBox.getItems(), model.serverListProperty());

        // Bind Status Label
        statusLabel.textProperty().bind(model.connectionStatusProperty());

        // Bind Button states and input fields based on connection status
        connectButton.textProperty().bind(Bindings.when(model.connectedProperty())
                .then("Disconnect")
                .otherwise("Connect"));

        sendButton.disableProperty().bind(model.connectedProperty().not());
        messageInput.disableProperty().bind(model.connectedProperty().not());

        // Disable server/nickname selection when connected
        serverComboBox.disableProperty().bind(model.connectedProperty());
        nicknameField.disableProperty().bind(model.connectedProperty());
        refreshButton.disableProperty().bind(model.connectedProperty());


        // Listen for new messages and append to TextArea
        model.chatMessagesProperty().addListener((ListChangeListener.Change<? extends String> change) -> {
            Platform.runLater(() -> { // Ensure UI update is on FX thread
                while (change.next()) {
                    if (change.wasAdded()) {
                        for (String msg : change.getAddedSubList()) {
                            chatArea.appendText(msg + "\n");
                        }
                    }
                }
            });
        });
    }


    @FXML
    private void handleConnectButton() {
        if (model.connectedProperty().get()) {
            // Currently connected -> Disconnect
            model.disconnect();
        } else {
            // Currently disconnected -> Connect
            ServerInfo selectedServer = serverComboBox.getSelectionModel().getSelectedItem();
            String nickname = nicknameField.getText();

            if (selectedServer == null) {
                showAlert(Alert.AlertType.WARNING, "Connection Error", "Please select a server.");
                return;
            }
            if (nickname == null || nickname.trim().isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Connection Error", "Please enter a nickname.");
                return;
            }

            model.connectToServer(selectedServer, nickname);
        }
    }

    @FXML
    private void handleRefreshButton() {
        serverComboBox.getItems().clear(); // Clear old list visually
        model.fetchServerList();
    }


    @FXML
    private void handleSendButton() {
        String message = messageInput.getText();
        if (message != null && !message.trim().isEmpty()) {
            model.sendMessage(message);
            messageInput.clear();
        }
        messageInput.requestFocus(); // Keep focus on input field
    }

    // Helper for showing alerts
    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> { // Show alert on FX thread
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }
}