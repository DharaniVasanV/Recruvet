package com.fakejobpostsystem.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class WhatsAppCloudApiService {

    private static final String GRAPH_BASE_URL = "https://graph.facebook.com/v23.0";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String accessToken;
    private final String phoneNumberId;

    public WhatsAppCloudApiService(
            ObjectMapper objectMapper,
            @Value("${whatsapp.access-token:${WHATSAPP_ACCESS_TOKEN:}}") String accessToken,
            @Value("${whatsapp.phone-number-id:${WHATSAPP_PHONE_NUMBER_ID:}}") String phoneNumberId) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.phoneNumberId = phoneNumberId == null ? "" : phoneNumberId.trim();
    }

    public boolean isConfigured() {
        return !accessToken.isBlank() && !phoneNumberId.isBlank();
    }

    public Path downloadMedia(String mediaId) throws IOException, InterruptedException {
        JsonNode metadata = fetchMediaMetadata(mediaId);
        String mediaUrl = metadata.path("url").asText("");
        String mimeType = metadata.path("mime_type").asText("");
        if (mediaUrl.isBlank()) {
            throw new IOException("WhatsApp media URL is missing");
        }

        HttpRequest mediaRequest = HttpRequest.newBuilder()
                .uri(URI.create(mediaUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(mediaRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String bodyPreview = response.body() == null ? "" : new String(response.body());
            throw new IOException("Failed to download WhatsApp media: HTTP " + response.statusCode() + " " + bodyPreview);
        }

        Path tempFile = Files.createTempFile("whatsapp-media-", extensionFor(mimeType));
        Files.write(tempFile, response.body());
        return tempFile;
    }

    public void sendTextMessage(String to, String body) throws IOException, InterruptedException {
        if (!isConfigured()) {
            throw new IOException("WhatsApp Cloud API is not configured");
        }

        Map<String, Object> requestBody = Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of(
                        "preview_url", true,
                        "body", body));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE_URL + "/" + phoneNumberId + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to send WhatsApp reply: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private JsonNode fetchMediaMetadata(String mediaId) throws IOException, InterruptedException {
        if (!isConfigured()) {
            throw new IOException("WhatsApp Cloud API is not configured");
        }
        if (mediaId == null || mediaId.isBlank()) {
            throw new IOException("WhatsApp media id is missing");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPH_BASE_URL + "/" + mediaId))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Failed to fetch WhatsApp media metadata: HTTP " + response.statusCode() + " " + response.body());
        }
        JsonNode metadata = objectMapper.readTree(response.body());
        System.out.println("WhatsApp media metadata fetched for id=" + mediaId + ", mime=" + metadata.path("mime_type").asText(""));
        return metadata;
    }

    private String extensionFor(String mimeType) {
        if (mimeType == null) {
            return ".jpg";
        }
        return switch (mimeType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
