package com.unilabs.chatroom_clientfx.model;

import com.unilabs.chatroom_clientfx.model.dto.RelayMessageDTO;
import com.unilabs.chatroom_clientfx.model.records.ServerInfo;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class Chat {

    private final String discoveryUrl;
    private final String relayUrl;
    private final String clientUuid;

    private final HttpClient httpClient;
    private ExecutorService networkExecutor; // For background network tasks
    private ScheduledExecutorService relayPollingExecutor; // Specific for polling

    // JavaFX Properties for UI binding/updates
    private final ListProperty<ServerInfo> serverList = new SimpleListProperty<>(FXCollections.observableArrayList());
    private final ListProperty<String> chatMessages = new SimpleListProperty<>(FXCollections.observableArrayList());
    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final StringProperty connectionStatus = new SimpleStringProperty("Disconnected");
    private final ObjectProperty<ConnectionMode> currentMode = new SimpleObjectProperty<>(ConnectionMode.NONE);
    private final StringProperty currentNickname = new SimpleStringProperty("");
    private final ObjectProperty<ServerInfo> currentServer = new SimpleObjectProperty<>(null);

    // Direct Connection Resources
    private Socket directSocket;
    private PrintWriter directWriter;
    private BufferedReader directReader;
    private Thread directReceiverThread;


    public Chat(String discoveryUrl, String relayUrl) {
        // Ensure URLs don't end with '/'
        this.discoveryUrl = discoveryUrl.endsWith("/") ? discoveryUrl.substring(0, discoveryUrl.length() - 1) : discoveryUrl;
        this.relayUrl = relayUrl.endsWith("/") ? relayUrl.substring(0, relayUrl.length() - 1) : relayUrl;
        this.clientUuid = UUID.randomUUID().toString();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.networkExecutor = Executors.newCachedThreadPool(); // Pool for general tasks
        System.out.println("Client UUID: " + clientUuid);
        updateStatus("Initialized. Client UUID: " + clientUuid);
    }

    // --- Property Getters for Controller ---
    public ReadOnlyListProperty<ServerInfo> serverListProperty() { return serverList; }
    public ReadOnlyListProperty<String> chatMessagesProperty() { return chatMessages; }
    public ReadOnlyBooleanProperty connectedProperty() { return connected; }
    public ReadOnlyStringProperty connectionStatusProperty() { return connectionStatus; }
    public ReadOnlyObjectProperty<ConnectionMode> currentModeProperty() { return currentMode; }
    public String getClientUuid() { return clientUuid; }


    // --- Core Actions Initiated by Controller ---

    public void setNickname(String nickname) {
        this.currentNickname.set(nickname.trim());
    }

    public void fetchServerList() {
        networkExecutor.submit(() -> {
            updateStatus("Fetching server list...");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(discoveryUrl + "/get_servers.php"))
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    List<ServerInfo> servers = ServerInfo.parseServerList(response.body());
                    Platform.runLater(() -> {
                        serverList.setAll(servers);
                        if (servers.isEmpty()) {
                            updateStatus("No active servers found.");
                        } else {
                            updateStatus("Server list updated. Please select a server.");
                        }
                    });
                } else {
                    handleNetworkError("Error fetching server list", "Status: " + response.statusCode(), null);
                }
            } catch (IOException | InterruptedException e) {
                handleNetworkError("Error connecting to discovery service", e.getMessage(), e);
            } catch (Exception e) { // Catch JSON parsing errors etc.
                handleNetworkError("Error processing server list", e.getMessage(), e);
            }
        });
    }

    public void connectToServer(ServerInfo server, String nickname) {
        if (connected.get()) {
            addChatMessage("[System] Already connected. Disconnect first.");
            return;
        }
        if (server == null) {
            addChatMessage("[Error] No server selected.");
            updateStatus("Connection failed: No server selected.");
            return;
        }
        if (nickname == null || nickname.trim().isEmpty()) {
            addChatMessage("[Error] Nickname cannot be empty.");
            updateStatus("Connection failed: Nickname required.");
            return;
        }

        setNickname(nickname);
        currentServer.set(server);
        updateStatus("Connecting to " + server.name() + " as " + nickname + "...");
        addChatMessage("[System] Attempting connection to: " + server.name());

        // Run connection logic in background
        networkExecutor.submit(() -> {
            boolean connectionEstablished = false;

            // 1. Try Direct
            if (!connectionEstablished && server.supportsDirect()) {
                updateStatus("Trying DIRECT connection to " + server.host() + ":" + server.port() + "...");
                if (attemptDirectConnection(server, nickname)) {
                    Platform.runLater(() -> {
                        currentMode.set(ConnectionMode.DIRECT);
                        connected.set(true);
                        updateStatus("Connected (DIRECT) to " + server.name());
                        addChatMessage("[System] Direct connection established!");
                        startDirectReceiverThread();
                    });
                    connectionEstablished = true;
                } else {
                    updateStatus("Direct connection failed. Trying Relay...");
                    addChatMessage("[System] Direct connection failed.");
                    // Ensure direct resources are cleaned even if relay is attempted
                    closeDirectConnectionResources();
                }
            }

            // 2. Try Relay
            if (!connectionEstablished && server.supportsRelay()) {
                updateStatus("Trying RELAY connection via " + relayUrl + "...");
                if (attemptRelayHandshake(server, nickname)) {
                    Platform.runLater(() -> {
                        currentMode.set(ConnectionMode.RELAY);
                        connected.set(true);
                        updateStatus("Connected (RELAY) to " + server.name());
                        addChatMessage("[System] Relay connection established!");
                        startRelayPolling();
                    });
                    connectionEstablished = true;
                } else {
                    updateStatus("Relay connection failed.");
                    addChatMessage("[System] Relay connection failed.");
                }
            }

            // 3. Handle Failure
            if (!connectionEstablished) {
                Platform.runLater(() -> {
                    updateStatus("Connection failed to " + server.name());
                    addChatMessage("[Error] Failed to connect to server '" + server.name() + "'.");
                    resetConnectionStateInternal(false); // Reset without explicit disconnect message
                });
            }
        });
    }

    public void sendMessage(String message) {
        if (!connected.get() || message == null || message.trim().isEmpty()) {
            // Maybe show a subtle error, or just ignore empty sends
            if (!connected.get()) addChatMessage("[Error] Not connected.");
            return;
        }

        String formattedMessage = "[" + currentNickname.get() + "] " + message; // Format for display locally immediately? Or let server do it? Let's let server do it for consistency.

        switch (currentMode.get()) {
            case DIRECT:
                sendDirectMessage(message); // Server adds nickname
                // Optionally add to local view immediately: addChatMessage(formattedMessage);
                break;
            case RELAY:
                sendRelayMessage(currentServer.get().uuid(), message, "chat");
                // Optionally add to local view immediately: addChatMessage(formattedMessage);
                break;
            case NONE:
                addChatMessage("[Error] Cannot send message: No active connection.");
                break;
        }
    }

    public void disconnect() {
        if (!connected.get()) return;

        addChatMessage("[System] Disconnecting...");
        updateStatus("Disconnecting...");

        // Send disconnect notification if possible (best effort)
        if(currentMode.get() == ConnectionMode.RELAY && currentServer.get() != null) {
            // Send a 'disconnect' control message (server needs to handle this)
            JSONObject disconnectPayload = new JSONObject();
            disconnectPayload.put("action", "CLIENT_DISCONNECT");
            // No need for executor here, it's a quick best-effort send
            sendRelayMessageInternal(currentServer.get().uuid(), disconnectPayload.toString(), "control");
        }
        // No standard TCP disconnect message defined, just close

        resetConnectionStateInternal(true); // Full reset with disconnect message
    }

    public void shutdown() {
        updateStatus("Shutting down...");
        disconnect(); // Ensure clean disconnect if connected
        networkExecutor.shutdown();
        stopRelayPolling(); // Ensure poller is stopped
        try {
            if (!networkExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                networkExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            networkExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        closeDirectConnectionResources(); // Final check
        System.out.println("ChatModel shutdown complete.");
    }


    // --- Internal Helper Methods ---

    private void updateStatus(String status) {
        Platform.runLater(() -> connectionStatus.set(status));
    }

    private void addChatMessage(String message) {
        Platform.runLater(() -> chatMessages.add(message));
    }

    private void handleNetworkError(String context, String details, Throwable t) {
        String errorMsg = "[Error] " + context + ": " + details;
        System.err.println(errorMsg);
        if (t != null) {
            t.printStackTrace(); // Log stack trace for debugging
        }
        // Update status and add message to chat window on FX thread
        Platform.runLater(() -> {
            updateStatus("Error: " + context);
            addChatMessage(errorMsg);
        });
    }

    private void resetConnectionStateInternal(boolean showDisconnectMessage) {
        // Run on FX thread to ensure property updates are safe
        Platform.runLater(() -> {
            boolean wasConnected = connected.get();
            connected.set(false);
            currentMode.set(ConnectionMode.NONE);
            currentServer.set(null);
            // Don't clear nickname
            // Clear chat messages? Optional - maybe keep history. Let's keep it for now. TODO
            // chatMessages.clear();
            if(wasConnected && showDisconnectMessage) {
                addChatMessage("[System] Disconnected.");
                updateStatus("Disconnected.");
            } else if (!wasConnected && !showDisconnectMessage) {
                // This happens after a connection attempt fails, status already updated
            }
            else {
                updateStatus("Disconnected."); // Generic fallback
            }

        });
        // Stop background activities
        stopRelayPolling();
        closeDirectConnectionResources(); // Close socket etc.
    }


    // --- Direct Connection Logic ---

    private boolean attemptDirectConnection(ServerInfo server, String nickname) {
        try {
            directSocket = new Socket();
            // Use host and port from the record
            directSocket.connect(new InetSocketAddress(server.host(), server.port()), 5000); // 5 sec timeout

            directWriter = new PrintWriter(directSocket.getOutputStream(), true, StandardCharsets.UTF_8);
            directReader = new BufferedReader(new InputStreamReader(directSocket.getInputStream(), StandardCharsets.UTF_8));

            // Handshake: UUID and Nickname
            directWriter.println(clientUuid);
            directWriter.println(nickname);

            // Wait for "OK" with timeout
            directSocket.setSoTimeout(5000);
            String response = directReader.readLine();
            directSocket.setSoTimeout(0); // Reset timeout

            if ("OK".equals(response)) {
                return true; // Success
            } else {
                addChatMessage("[Error] Server rejected direct connection: " + (response == null ? "No response" : response));
                closeDirectConnectionResources();
                return false;
            }
        } catch (SocketTimeoutException e) {
            addChatMessage("[Error] Direct connection timed out.");
            System.err.println("Direct connection attempt timed out: " + e.getMessage());
            closeDirectConnectionResources();
            return false;
        } catch (ConnectException e) {
            addChatMessage("[Error] Direct connection refused by server.");
            System.err.println("Direct connection refused: " + e.getMessage());
            closeDirectConnectionResources();
            return false;
        } catch (IOException e) {
            addChatMessage("[Error] IO error during direct connection.");
            System.err.println("IO Error during direct connection attempt: " + e.getMessage());
            closeDirectConnectionResources();
            return false;
        } catch (Exception e) { // Catch unexpected errors
            addChatMessage("[Error] Unexpected error during direct connection.");
            System.err.println("Unexpected error during direct connection: " + e.getMessage());
            e.printStackTrace();
            closeDirectConnectionResources();
            return false;
        }
    }

    private void startDirectReceiverThread() {
        // Ensure previous thread is stopped if any
        if (directReceiverThread != null && directReceiverThread.isAlive()) {
            directReceiverThread.interrupt(); // Interrupt previous thread
        }

        directReceiverThread = new Thread(() -> {
            try {
                String serverMessage;
                // Check connected flag and reader status
                while (connected.get() && currentMode.get() == ConnectionMode.DIRECT && directReader != null && (serverMessage = directReader.readLine()) != null) {
                    final String msg = serverMessage; // Final variable for lambda
                    addChatMessage(msg); // Add message via Platform.runLater
                }
            } catch (SocketException e) {
                if (connected.get()) { // Avoid error message if disconnect was intentional
                    System.err.println("SocketException in Direct Receiver: " + e.getMessage());
                    handleNetworkError("Direct connection lost", "Socket closed", e);
                }
            } catch (IOException e) {
                if (connected.get()) {
                    System.err.println("IOException in Direct Receiver: " + e.getMessage());
                    handleNetworkError("Direct connection error", "Error reading from server", e);
                }
            } catch (Exception e) { // Catch unexpected errors
                if (connected.get()) {
                    System.err.println("Unexpected error in Direct Receiver: " + e.getMessage());
                    handleNetworkError("Direct connection error", "Unexpected error reading", e);
                }
            } finally {
                System.out.println("Direct Receiver Thread exiting.");
                // If the thread stops unexpectedly while we think we are connected, trigger disconnect
                if (connected.get() && currentMode.get() == ConnectionMode.DIRECT) {
                    System.err.println("Direct receiver thread terminated unexpectedly. Disconnecting.");
                    // Use Platform.runLater for the UI/state update part of reset
                    Platform.runLater(() -> resetConnectionStateInternal(true));
                }
            }
        }, "Direct-Receiver-Thread");
        directReceiverThread.setDaemon(true); // Allow JVM to exit if only this thread is running
        directReceiverThread.start();
        addChatMessage("[System] Direct message listener started.");
    }

    private void sendDirectMessage(String message) {
        // Run send operation in background to avoid blocking UI thread
        networkExecutor.submit(() -> {
            if (connected.get() && currentMode.get() == ConnectionMode.DIRECT && directWriter != null) {
                // The message from the UI doesn't need the nickname prepended here,
                // the server should handle adding the sender info.
                directWriter.println(message);
                if (directWriter.checkError()) {
                    // Error occurred during send, likely connection lost
                    System.err.println("Error sending direct message. Connection may be lost.");
                    // Use Platform.runLater for the UI/state update part of reset
                    Platform.runLater(() -> {
                        addChatMessage("[Error] Failed to send message. Connection lost.");
                        resetConnectionStateInternal(true);
                    });
                }
                // Optionally add the sent message locally IF the server doesn't echo it back
                // addChatMessage("[" + currentNickname.get() + "] " + message);
            } else if (connected.get()) {
                addChatMessage("[Error] Cannot send direct message: Writer not available.");
            }
        });
    }

    private void closeDirectConnectionResources() {
        // Stop the receiver thread first
        if (directReceiverThread != null && directReceiverThread.isAlive()) {
            directReceiverThread.interrupt(); // Signal thread to stop
            try {
                directReceiverThread.join(500); // Wait briefly for it to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        directReceiverThread = null; // Release reference

        // Close streams and socket, ignoring errors
        try { if (directReader != null) directReader.close(); } catch (IOException e) { /* ignore */ }
        // Don't close writer typically, closing socket handles it. Can cause issues if closed separately.
        // try { if (directWriter != null) directWriter.close(); } catch (Exception e) { /* ignore */ }
        try { if (directSocket != null && !directSocket.isClosed()) directSocket.close(); } catch (IOException e) { /* ignore */ }

        directReader = null;
        directWriter = null;
        directSocket = null;
        System.out.println("Direct connection resources closed.");
    }


    // --- Relay Connection Logic ---

    private boolean attemptRelayHandshake(ServerInfo server, String nickname) {
        // This whole process involves waiting, so run it off the FX thread,
        // but it was already called within an executor task in connectToServer.

        // 1. Send HANDSHAKE_REQUEST
        JSONObject handshakePayload = new JSONObject();
        handshakePayload.put("action", "HANDSHAKE_REQUEST");
        handshakePayload.put("nickname", nickname); // Send nickname

        if (!sendRelayMessageInternal(server.uuid(), handshakePayload.toString(), "control")) {
            addChatMessage("[Error] Failed to send relay handshake request.");
            return false;
        }
        addChatMessage("[System] Relay handshake request sent. Waiting for response...");

        // 2. Poll for HANDSHAKE_OK (synchronously within this background task)
        long startTime = System.currentTimeMillis();
        long timeoutMillis = 10000; // 10 seconds

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            List<JSONObject> messages = pollRelayMessagesInternal(); // Synchronous poll
            if (messages != null) {
                for (JSONObject msg : messages) {
                    RelayMessageDTO dto = RelayMessageDTO.fromJson(msg);
                    if (dto != null && server.uuid().equals(dto.getSender()) && "control".equalsIgnoreCase(dto.getType())) {
                        try {
                            JSONObject controlMsg = new JSONObject(dto.getMessage());
                            String action = controlMsg.optString("action");

                            if ("HANDSHAKE_OK".equalsIgnoreCase(action)) {
                                addChatMessage("[System] Received HANDSHAKE_OK from server.");
                                return true; // Success!
                            } else if ("HANDSHAKE_ERROR".equalsIgnoreCase(action)) {
                                String reason = controlMsg.optString("reason", "Unknown reason");
                                addChatMessage("[Error] Relay handshake rejected: " + reason);
                                return false;
                            }
                        } catch (Exception e) {
                            System.err.println("Error parsing relay control message during handshake: " + e.getMessage());
                            // Continue polling
                        }
                    }
                    // Ignore other messages during handshake
                }
            } else {
                // Polling failed, likely a connection issue
                addChatMessage("[Error] Failed to poll relay service during handshake.");
                return false;
            }

            try {
                TimeUnit.MILLISECONDS.sleep(500); // Wait before next poll
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                addChatMessage("[System] Handshake interrupted.");
                return false;
            }
        }

        addChatMessage("[Error] Relay handshake timed out.");
        return false; // Timeout
    }

    private void startRelayPolling() {
        stopRelayPolling(); // Ensure any previous poller is stopped

        relayPollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true); // Allow JVM exit
            t.setName("Relay-Poller-Thread");
            return t;
        });

        relayPollingExecutor.scheduleAtFixedRate(() -> {
            // Check connection status before polling
            if (!connected.get() || currentMode.get() != ConnectionMode.RELAY) {
                // This check might be redundant if stopRelayPolling is called correctly on disconnect,
                // but it's a safeguard.
                // stopRelayPolling(); // Stop itself - careful with recursive calls or race conditions, maybe better options later
                // It's better to rely on the external disconnect logic to call stopRelayPolling.
                return;
            }

            try {
                List<JSONObject> messages = pollRelayMessagesInternal(); // Use internal synchronous version
                if (messages != null) {
                    for (JSONObject msg : messages) {
                        RelayMessageDTO dto = RelayMessageDTO.fromJson(msg);
                        if (dto != null) {
                            // Process message on FX thread
                            Platform.runLater(() -> processIncomingRelayMessage(dto));
                        }
                    }
                }
                // If pollRelayMessagesInternal returns null, it means an error occurred and was likely handled (e.g., disconnect triggered)
            } catch (Exception e) {
                // Catch any unexpected errors during polling task execution
                System.err.println("Unexpected error in relay polling task: " + e.getMessage());
                e.printStackTrace();
                // Consider triggering disconnect on persistent errors
                handleRelayConnectionError();
            }

        }, 0, 3, TimeUnit.SECONDS); // Poll every 3 seconds

        addChatMessage("[System] Relay message polling started.");
    }

    private void processIncomingRelayMessage(RelayMessageDTO dto) {
        // Ensure we are still connected in relay mode before processing
        if (!connected.get() || currentMode.get() != ConnectionMode.RELAY) {
            return;
        }

        // We expect messages primarily from the server we are connected to
        if (currentServer.get() != null && currentServer.get().uuid().equals(dto.getSender())) {
            switch (dto.getType().toLowerCase()) {
                case "chat":
                case "system":
                    // Server should have formatted this already (e.g., "[Nick] msg" or "[SERVER] info")
                    addChatMessage(dto.getMessage());
                    break;
                case "control":
                    // Handle control messages from server if needed (e.g., server shutdown warning)
                    addChatMessage("[Control from Server]: " + dto.getMessage());
                    try {
                        JSONObject controlJson = new JSONObject(dto.getMessage());
                        if ("SERVER_SHUTDOWN".equals(controlJson.optString("action"))) {
                            addChatMessage("[System] Server is shutting down!");
                            // Optionally trigger disconnect automatically
                            // resetConnectionStateInternal(true);
                        }
                    } catch (Exception e) { /* Ignore parse error */ }
                    break;
                default:
                    addChatMessage("[Unknown Type from Server] " + dto.getMessage());
                    break;
            }
        } else {
            // Message from unexpected sender? Log it.
            System.out.println("Received relay message from unexpected sender: " + dto.getSender() + " (Expected: " + (currentServer.get() != null ? currentServer.get().uuid() : "N/A") + ")");
            // Maybe display it?
            // addChatMessage("[" + dto.getSender().substring(0, 6) + "?] " + dto.getMessage());
        }
    }


    // Internal method for synchronous polling (used by handshake and poller thread)
    private List<JSONObject> pollRelayMessagesInternal() {
        String encodedUuid;
        try {
            encodedUuid = URLEncoder.encode(clientUuid, StandardCharsets.UTF_8);
        } catch (Exception e) { // Should not happen with UTF-8
            System.err.println("Error encoding client UUID: " + e.getMessage());
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relayUrl + "/get_messages.php?recipient=" + encodedUuid))
                .GET()
                .timeout(Duration.ofSeconds(5)) // Shorter timeout for polling
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                String body = response.body();
                if (body != null && !body.trim().isEmpty() && !body.trim().equals("[]")) {
                    JSONArray jsonArray = new JSONArray(body);
                    List<JSONObject> messageList = new java.util.ArrayList<>();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        messageList.add(jsonArray.getJSONObject(i));
                    }
                    return messageList;
                }
                return List.of(); // Empty list if no messages
            } else {
                System.err.println("Error polling relay. Status: " + response.statusCode());
                // Consider this a connection error if it persists
                handleRelayConnectionError(); // Trigger disconnect
                return null; // Indicate error
            }
        } catch (IOException | InterruptedException e) {
            if (connected.get() && currentMode.get() == ConnectionMode.RELAY) { // Only log if expecting connection
                System.err.println("Error connecting to relay service for polling: " + e.getMessage());
                handleRelayConnectionError(); // Trigger disconnect
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null; // Indicate error
        } catch (Exception e) { // Catch JSON parsing errors etc.
            if (connected.get() && currentMode.get() == ConnectionMode.RELAY) {
                System.err.println("Error parsing relay messages: " + e.getMessage());
                // Don't necessarily disconnect for a parse error, maybe log and continue
                addChatMessage("[Error] Could not parse message from relay.");
            }
            return null; // Indicate error, but maybe less severe than connection loss
        }
    }

    private void sendRelayMessage(String recipientUuid, String message, String type) {
        // Run send operation in background
        networkExecutor.submit(() -> {
            if (!sendRelayMessageInternal(recipientUuid, message, type)) {
                // Error was already logged in internal method, maybe add chat message
                addChatMessage("[Error] Failed to send message via relay.");
                // Internal method handles triggering disconnect on send failure too
            }
            // Optionally add the sent message locally IF the server doesn't echo it back
            // if ("chat".equals(type)) {
            //     addChatMessage("[" + currentNickname.get() + "] " + message);
            // }
        });
    }

    // Internal synchronous send method
    private boolean sendRelayMessageInternal(String recipientUuid, String message, String type) {
        if (!connected.get() && !"control".equals(type)) { // Allow sending control messages like disconnect even if state slightly outdated
            System.err.println("Cannot send relay message, not connected.");
            return false;
        }

        RelayMessageDTO dto = new RelayMessageDTO(clientUuid, recipientUuid, message, type);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relayUrl + "/send_message.php"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(dto.toJsonString(), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(5))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 202 Accepted is expected success
            if (response.statusCode() == 202) {
                return true;
            } else {
                System.err.println("Failed to send relay message. Status: " + response.statusCode() + ", Body: " + response.body());
                handleRelayConnectionError(); // Assume connection issue on send failure
                return false;
            }
        } catch (IOException | InterruptedException e) {
            if (connected.get() && currentMode.get() == ConnectionMode.RELAY) { // Only log if expecting connection
                System.err.println("Error connecting to relay service for sending: " + e.getMessage());
                handleRelayConnectionError(); // Assume connection issue
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) { // Catch unexpected errors
            if (connected.get() && currentMode.get() == ConnectionMode.RELAY) {
                System.err.println("Unexpected error sending relay message: " + e.getMessage());
                e.printStackTrace();
                // Consider it a connection error? Maybe.
                handleRelayConnectionError();
            }
            return false;
        }
    }

    private void handleRelayConnectionError() {
        // Only trigger reset if we are currently connected via relay
        if (connected.get() && currentMode.get() == ConnectionMode.RELAY) {
            System.err.println("Relay connection error detected. Disconnecting.");
            // Use Platform.runLater for the UI/state update part of reset
            Platform.runLater(() -> {
                addChatMessage("[Error] Lost connection to Relay service.");
                resetConnectionStateInternal(true);
            });
        }
    }


    private void stopRelayPolling() {
        if (relayPollingExecutor != null && !relayPollingExecutor.isShutdown()) {
            relayPollingExecutor.shutdown();
            try {
                if (!relayPollingExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    relayPollingExecutor.shutdownNow();
                }
                System.out.println("Relay polling stopped.");
            } catch (InterruptedException e) {
                relayPollingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                relayPollingExecutor = null;
            }
        }
    }
}