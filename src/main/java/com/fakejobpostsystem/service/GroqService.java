package com.fakejobpostsystem.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.dto.GroqVerificationResult;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.dto.ScamReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class GroqService {

    private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public GroqService(
            ObjectMapper objectMapper,
            @Value("${GROQ_API_KEY:${Groq_api_key:}}") String apiKey,
            @Value("${GROQ_MODEL:llama-3.3-70b-versatile}") String model) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "llama-3.3-70b-versatile" : model.trim();
    }

    public GroqVerificationResult verify(String companyName, String documentText) {
        if (apiKey.isBlank() || companyName == null || companyName.isBlank()) {
            return null;
        }

        String prompt = buildPrompt(companyName, documentText);
        try {
            Map<String, Object> requestBodyMap = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", "You are an expert fraud detection system specialized in identifying fake job offers and scam companies. Return only strict JSON."),
                            Map.of("role", "user", "content", prompt)
                    ),
                    "response_format", Map.of("type", "json_object"),
                    "temperature", 0.1
            );

            String requestBody = objectMapper.writeValueAsString(requestBodyMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Groq API error: " + response.statusCode() + " " + response.body());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                return null;
            }

            JsonNode verificationNode = objectMapper.readTree(content);
            
            boolean isLegit = verificationNode.path("is_legit").asBoolean(true);
            double riskScore = verificationNode.path("risk_score").asDouble(0.0);
            String summary = verificationNode.path("summary").asText("No summary provided.");
            
            List<RedFlagCheck> redFlags = new ArrayList<>();
            JsonNode redFlagsNode = verificationNode.path("red_flags");
            if (redFlagsNode.isArray()) {
                for (JsonNode node : redFlagsNode) {
                    redFlags.add(new RedFlagCheck(
                            node.path("flag").asText("Unknown Flag"),
                            node.path("evidence").asText("Analysis of document"),
                            node.path("explanation").asText("Potential risk")
                    ));
                }
            }

            List<ScamReport> reports = new ArrayList<>();
            JsonNode reportsNode = verificationNode.path("scam_reports");
            if (reportsNode.isArray()) {
                for (JsonNode node : reportsNode) {
                    reports.add(new ScamReport(
                            node.path("title").asText("Report"),
                            node.path("search_query").asText(""),
                            node.path("url").asText(""),
                            node.path("platform").asText("Link")
                    ));
                }
            }

            return new GroqVerificationResult(isLegit, riskScore, summary, redFlags, reports);

        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private String buildPrompt(String companyName, String documentText) {
        return """
                Analyze the legitimacy of the company "%s" and the job/internship offer below.
                Check for known scams (e.g. Bluestock Fintech, GrrowUp), fraudulent patterns, or negative reports on Reddit, Glassdoor, LinkedIn, AmbitionBox, and Quora.
                
                Document Text:
                %s
                
                Return a JSON object with this exact structure:
                {
                  "is_legit": boolean,
                  "risk_score": number (0.0 to 1.0),
                  "summary": "Concise summary of findings",
                  "red_flags": [
                    {
                      "flag": "Short name of the red flag (e.g. Unsolicited outreach)",
                      "evidence": "How it specifically appears in this case/text (e.g. HR e-mail sent via Gmail)",
                      "explanation": "Why this matters in a professional context"
                    }
                  ],
                  "scam_reports": [
                    {
                      "title": "Short descriptive title",
                      "search_query": "Specific phrase to search on the platform (e.g. 'Bluestock Fintech scam reddit')",
                      "url": "OPTIONAL: Only if you are 100%% certain this URL is EXACTLY for this company",
                      "platform": "Reddit, LinkedIn, Glassdoor, etc."
                    }
                  ]
                }
                
                STRICT RULES:
                1. If you find NO negative reports for the SPECIFIC company name provided, 'scam_reports' MUST be an empty array [].
                2. Do NOT hallucinate URLs. It is BETTER to provide a 'search_query' than a broken URL.
                3. If the company is unknown but the job post text looks professional, use risk_score: 0.1 or 0.2.
                4. Only use risk_score > 0.5 if you find SPECIFIC red flags in the job text or SPECIFIC negative reports.
                
                Reference Red-Flags to look for:
                - Non-corporate sender addresses (gmail.com, yahoo.com)
                - Payment requests for ID cards/onboarding
                - No interview or technical screening claims
                - WhatsApp/Telegram group flooding tactics
                - Generic or error-prone PDF offer letters
                - Lack of official website career listings
                
                If you find specific reports or discussions about this company (especially on Reddit or LinkedIn), include the most relevant links.
                
                Scoring Rubric:
                - 0.0 to 0.2: Well-known legitimate company with official presence and professional outreach.
                - 0.3 to 0.5: General red flags found (e.g. gmail, unpaid) but the company might be a small startup. No negative reports found.
                - 0.6 to 0.8: Multiple strong red flags OR specific negative discussions found on forums.
                - 0.9 to 1.0: Confirmed scam company/offer with direct links to multiple victim reports.
                """.formatted(companyName, documentText);
    }

    public boolean isEnabled() {
        return !apiKey.isBlank();
    }
}
