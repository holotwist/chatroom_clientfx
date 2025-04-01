package com.unilabs.chatroom_clientfx.model.dto;

import org.json.JSONObject;

// Using a standard DTO class for relay messages

/**
 * Class that represents a message (any type) sent or received from the relay
 */
public class RelayMessageDTO {
    private String sender;
    private String recipient;
    private String message;
    private String type; // e.g., "chat", "control", "system"

    public RelayMessageDTO(String sender, String recipient, String message, String type) {
        this.sender = sender;
        this.recipient = recipient;
        this.message = message;
        this.type = type;
    }

    // Getters (Setters might not be needed if created once)
    public String getSender() { return sender; }
    public String getRecipient() { return recipient; }
    public String getMessage() { return message; }
    public String getType() { return type; }

    // Method to convert DTO to JSON for sending

    /**
     * Convert string data to JSON payload to be sent
     *
     * @return the JSON data
     */
    public String toJsonString() {
        JSONObject payload = new JSONObject();
        payload.put("sender", sender);
        payload.put("recipient", recipient);
        payload.put("message", message);
        payload.put("type", type);
        return payload.toString();
    }

    /**
     * Static factory method to parse from received JSON
     *
     * @param json the JSON object with payload
     * @return String data
     */
    public static RelayMessageDTO fromJson(JSONObject json) {
        if (json == null) return null;
        try {
            return new RelayMessageDTO(
                    json.optString("sender", null),
                    json.optString("recipient", null), // Recipient might not always be present in received msg
                    json.optString("message", ""),
                    json.optString("type", "chat") // Default type if missing
            );
        } catch (Exception e) {
            System.err.println("Error parsing RelayMessageDTO from JSON: " + e.getMessage());
            return null;
        }
    }
}