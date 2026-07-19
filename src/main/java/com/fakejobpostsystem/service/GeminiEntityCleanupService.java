package com.fakejobpostsystem.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.dto.Entities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class GeminiEntityCleanupService {

    private static final String API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final int MAX_DOCUMENT_CHARS = 7000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final List<String> models;

    public GeminiEntityCleanupService(
            ObjectMapper objectMapper,
            @Value("${GEMINI_API_KEY:${Gemini_api_key:}}") String apiKey,
            @Value("${app.gemini-models:gemini-2.5-flash,gemini-2.5-flash-lite,gemini-1.5-flash}") String modelsProperty) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.models = parseModels(modelsProperty);
    }

    public EntityCleanupResult cleanup(String documentText, Entities heuristicEntities, String heuristicCompanyName) {
        if (apiKey.isBlank() || documentText == null || documentText.isBlank()) {
            return null;
        }

        String prompt = buildPrompt(documentText, heuristicEntities, heuristicCompanyName);
        for (String model : models) {
            EntityCleanupResult result = callModel(model, prompt);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private EntityCleanupResult callModel(String model, String prompt) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "systemInstruction", Map.of(
                            "parts", List.of(Map.of(
                                    "text", """
                                            You are an information extraction system for OCR-heavy recruitment documents.
                                            Return only strict JSON with trustworthy entities.
                                            Never invent missing emails, websites, names, or companies.
                                            """))),
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.1)));

            String endpoint = String.format(API_URL_TEMPLATE, model)
                    + "?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(25))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode partsNode = root.path("candidates").path(0).path("content").path("parts");
            String payloadText = joinTextParts(partsNode);
            if (payloadText == null || payloadText.isBlank()) {
                return null;
            }

            String jsonPayload = extractJsonObject(payloadText);
            if (jsonPayload == null) {
                return null;
            }

            JsonNode entityNode = objectMapper.readTree(jsonPayload);
            Entities entities = new Entities(
                    sanitizeList(entityNode.path("phones")),
                    sanitizeList(entityNode.path("emails")),
                    sanitizeList(entityNode.path("persons")),
                    sanitizeList(entityNode.path("organizations")));

            String companyName = sanitizeString(entityNode.path("companyName").asText(null));
            if (entities.emails().isEmpty()
                    && entities.phones().isEmpty()
                    && entities.persons().isEmpty()
                    && entities.organizations().isEmpty()
                    && companyName == null) {
                return null;
            }
            return new EntityCleanupResult(entities, companyName, model);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private String buildPrompt(String documentText, Entities heuristicEntities, String heuristicCompanyName) {
        String heuristicJson;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("phones", heuristicEntities == null ? List.of() : heuristicEntities.phones());
            payload.put("emails", heuristicEntities == null ? List.of() : heuristicEntities.emails());
            payload.put("persons", heuristicEntities == null ? List.of() : heuristicEntities.persons());
            payload.put("organizations", heuristicEntities == null ? List.of() : heuristicEntities.organizations());
            payload.put("companyName", heuristicCompanyName);
            heuristicJson = objectMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            heuristicJson = "{}";
        }

        return """
                Clean OCR entity extraction for a job-related, internship-related, employment-related, or recommendation document.
                Return ONLY valid JSON with this exact shape:
                {
                  "phones": ["..."],
                  "emails": ["..."],
                  "persons": ["..."],
                  "organizations": ["..."],
                  "companyName": "..." 
                }

                Rules:
                - Do not invent facts.
                - Prefer entities explicitly present in the OCR text.
                - Use the heuristic extraction only as a hint, not as ground truth.
                - Remove headings, generic words, addresses, dates, and role titles from organizations.
                - Put only real human names in persons. Do not put job titles, headings, or sentence fragments there.
                - persons must mean contact-side people only: recruiter, HR, hiring manager, sender, signatory, recommender, referee, or person to contact.
                - Do not put the applicant, candidate, student, recipient, intern, or person being offered the role into persons.
                - If a letter says "Dear X", "Letter to X", or "offer you the position", treat X as the recipient or candidate, not a contact person.
                - If a name appears near a signature, closing, HR title, recruiter title, manager title, professor title, or contact wording, prefer that name for persons.
                - Keep universities, institutes, colleges, companies, firms, and partnerships in organizations if explicitly named.
                - companyName should be the best-fit employer, institution, or organization mentioned in the document, or null if unclear.
                - companyName must be the clean brand/institution name only, without legal suffixes such as Private Limited, Pvt Ltd, Limited, Ltd, LLC, Inc, Corp, Corporation, or Company.
                - Example: return "Jacav Technologies", not "Jacav Technologies Private Limited".
                - If the document is a letter, recommendation, offer letter, internship letter, or referral, prefer named institutions or companies over generic nouns like "Candidate" or headings.
                - Keep arrays unique and concise.
                - If unsure, return an empty array or null instead of guessing.

                OCR text:
                %s

                Heuristic extraction:
                %s
                """.formatted(limitDocument(documentText), heuristicJson);
    }

    private List<String> parseModels(String modelsProperty) {
        List<String> parsed = new ArrayList<>();
        for (String model : modelsProperty.split(",")) {
            String trimmed = model.trim();
            if (!trimmed.isBlank()) {
                parsed.add(trimmed);
            }
        }
        return parsed;
    }

    private String joinTextParts(JsonNode partsNode) {
        if (partsNode == null || !partsNode.isArray()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (JsonNode part : partsNode) {
            String value = sanitizeString(part.path("text").asText(null));
            if (value == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private String extractJsonObject(String responseText) {
        int start = responseText.indexOf('{');
        int end = responseText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return responseText.substring(start, end + 1);
    }

    private List<String> sanitizeList(JsonNode node) {
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String value = sanitizeString(item.asText());
                if (value != null) {
                    cleaned.add(value);
                }
            }
        }
        return List.copyOf(cleaned);
    }

    private String sanitizeString(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank() || "null".equalsIgnoreCase(cleaned)) {
            return null;
        }
        return cleaned;
    }

    private String limitDocument(String text) {
        String normalized = text == null ? "" : text.trim();
        return normalized.length() <= MAX_DOCUMENT_CHARS
                ? normalized
                : normalized.substring(0, MAX_DOCUMENT_CHARS);
    }

    public boolean isEnabled() {
        return !apiKey.isBlank();
    }

    public record EntityCleanupResult(Entities entities, String companyName, String modelUsed) {
    }
}
