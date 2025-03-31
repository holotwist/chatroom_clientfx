package com.unilabs.chatroom_clientfx.model.records;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// Using a Record for immutable server information
public record ServerInfo(String uuid, String name, String host, int port, List<String> supportedMethods) {

    // Constructor with validation/defensive copying
    public ServerInfo {
        Objects.requireNonNull(uuid, "uuid cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(host, "host cannot be null");
        Objects.requireNonNull(supportedMethods, "supportedMethods cannot be null");
        // Make the list immutable
        supportedMethods = Collections.unmodifiableList(new ArrayList<>(supportedMethods));
    }

    // Convenience methods
    public boolean supportsDirect() {
        return supportedMethods.contains("direct");
    }

    public boolean supportsRelay() {
        return supportedMethods.contains("relay");
    }

    // toString for display in ComboBox/ListView
    @Override
    public String toString() {
        return name + " (" + String.join("/", supportedMethods) + ")";
    }

    // Parse the server list
    public static List<ServerInfo> parseServerList(String jsonString) {
        List<ServerInfo> serverList = new ArrayList<>();
        if (jsonString == null || jsonString.trim().isEmpty()) {
            System.err.println("Error parsing server list: JSON string is empty or null.");
            return serverList;
        }
        try {
            JSONArray servers = new JSONArray(jsonString);
            for (int i = 0; i < servers.length(); i++) {
                JSONObject server = servers.getJSONObject(i);
                List<String> methods = new ArrayList<>();
                if (server.has("supported_methods")) {
                    JSONArray methodsArray = server.getJSONArray("supported_methods");
                    for (int j = 0; j < methodsArray.length(); j++) {
                        methods.add(methodsArray.getString(j));
                    }
                }
                // Ensure all required fields are present
                if (server.has("uuid") && server.has("name") && server.has("host") && server.has("port") && !methods.isEmpty()) {
                    // Basic validation for port to avoid crashes later
                    int port = server.getInt("port");
                    if (port > 0 && port <= 65535) {
                        serverList.add(new ServerInfo(
                                server.getString("uuid"),
                                server.getString("name"),
                                server.getString("host"),
                                port,
                                methods
                        ));
                    } else {
                        System.err.println("Skipping server entry with invalid port: " + server.toString());
                    }
                } else {
                    System.err.println("Skipping incomplete server entry: " + server.toString());
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing server list JSON: " + e.getMessage());
            // Return potentially partially filled list or empty list on error
        }
        return serverList;
    }
}