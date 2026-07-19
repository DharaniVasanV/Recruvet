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
import java.util.Locale;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.dto.ReviewEvidenceDebug;
import com.fakejobpostsystem.dto.ReviewVerificationResult;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.dto.RedFlagCheck.SignalCategory;
import com.fakejobpostsystem.dto.ScamReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class ReviewVerificationService {

    private static final String SERPER_SEARCH_URL = "https://google.serper.dev/search";
    private static final String GEMINI_API_URL_TEMPLATE = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final int MAX_PLATFORM_RESULTS = 3;
    private static final int MAX_EXCERPT_CHARS = 1800;
    private static final List<PlatformQuery> REVIEW_PLATFORMS = List.of(
            new PlatformQuery("Reddit", "site:reddit.com \"%s\" company reviews OR scam OR fake offer"),
            new PlatformQuery("Indeed", "site:indeed.com/cmp \"%s\" reviews"),
            new PlatformQuery("Glassdoor", "site:glassdoor.com \"%s\" reviews")
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String serperApiKey;
    private final String geminiApiKey;
    private final String geminiModel;

    public ReviewVerificationService(
            ObjectMapper objectMapper,
            @Value("${SERPER_API_KEY:${Serper_api_key:}}") String serperApiKey,
            @Value("${GEMINI_API_KEY:${Gemini_api_key:}}") String geminiApiKey,
            @Value("${app.gemini-models:gemini-2.5-flash,gemini-2.5-flash-lite,gemini-1.5-flash}") String geminiModels) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.serperApiKey = serperApiKey == null ? "" : serperApiKey.trim();
        this.geminiApiKey = geminiApiKey == null ? "" : geminiApiKey.trim();
        this.geminiModel = firstModel(geminiModels);
    }

    public ReviewVerificationResult verify(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            return emptyResult("No company name was detected for review verification.", Map.of(
                    "Reddit", "Unavailable",
                    "Indeed", "Unavailable",
                    "Glassdoor", "Unavailable"));
        }

        if (serperApiKey.isBlank() || geminiApiKey.isBlank()) {
            return emptyResult("Review verification is unavailable because API keys are missing.", Map.of(
                    "Reddit", "Unavailable",
                    "Indeed", "Unavailable",
                    "Glassdoor", "Unavailable"));
        }

        EvidenceCollection evidenceCollection = collectEvidence(companyName);
        if (evidenceCollection.evidence().isEmpty()) {
            return emptyResult("No public review evidence was found for this company from the configured platforms.",
                    evidenceCollection.evidenceTypes());
        }

        ReviewVerificationResult geminiResult = analyzeEvidenceWithGemini(companyName, evidenceCollection);
        if (geminiResult != null) {
            return geminiResult;
        }

        return heuristicFallback(evidenceCollection);
    }

    private EvidenceCollection collectEvidence(String companyName) {
        LinkedHashSet<SearchEvidence> evidence = new LinkedHashSet<>();
        Map<String, String> evidenceTypes = new LinkedHashMap<>();
        REVIEW_PLATFORMS.forEach(platform -> evidenceTypes.put(platform.platform(), "No Match"));

        for (PlatformQuery platformQuery : REVIEW_PLATFORMS) {
            try {
                String query = platformQuery.queryTemplate().formatted(companyName);
                String requestBody = objectMapper.writeValueAsString(Map.of("q", query, "gl", "in"));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(SERPER_SEARCH_URL))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-API-KEY", serperApiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    evidenceTypes.put(platformQuery.platform(), "Search Failed");
                    continue;
                }

                JsonNode organic = objectMapper.readTree(response.body()).path("organic");
                if (!organic.isArray()) {
                    evidenceTypes.put(platformQuery.platform(), "Search Failed");
                    continue;
                }

                int kept = 0;
                for (JsonNode result : organic) {
                    if (kept >= MAX_PLATFORM_RESULTS) {
                        break;
                    }

                    String title = sanitize(result.path("title").asText(null));
                    String snippet = sanitize(result.path("snippet").asText(null));
                    String link = sanitize(result.path("link").asText(null));
                    if (title == null || link == null) {
                        continue;
                    }
                    if (!looksRelevant(companyName, title + " " + snippet + " " + link)) {
                        continue;
                    }

                    ScrapedContent scraped = scrapePublicEvidence(platformQuery.platform(), link, companyName);
                    String excerpt = scraped == null ? snippet : scraped.excerpt();
                    String evidenceType = scraped == null ? "search-snippet-fallback" : scraped.evidenceType();

                    if (excerpt == null || excerpt.isBlank()) {
                        continue;
                    }

                    evidence.add(new SearchEvidence(platformQuery.platform(), title, snippet, link, evidenceType, excerpt));
                    evidenceTypes.put(platformQuery.platform(), updatePlatformType(
                            evidenceTypes.get(platformQuery.platform()), evidenceType));
                    kept++;
                }
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                evidenceTypes.put(platformQuery.platform(), "Search Failed");
            }
        }
        return new EvidenceCollection(new ArrayList<>(evidence), evidenceTypes);
    }

    private ScrapedContent scrapePublicEvidence(String platform, String url, String companyName) {
        return switch (platform.toLowerCase(Locale.ROOT)) {
            case "reddit" -> scrapeReddit(url, companyName);
            case "indeed" -> scrapeHtmlPlatform(url, companyName, "indeed-public-html");
            case "glassdoor" -> scrapeHtmlPlatform(url, companyName, "glassdoor-public-html");
            default -> null;
        };
    }

    private ScrapedContent scrapeReddit(String url, String companyName) {
        try {
            String jsonUrl = buildRedditJsonUrl(url);
            if (jsonUrl == null) {
                return null;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(jsonUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.isArray() || root.size() < 2) {
                return null;
            }

            StringBuilder excerpt = new StringBuilder();
            JsonNode postData = root.path(0).path("data").path("children").path(0).path("data");
            appendIfRelevant(excerpt, sanitize(postData.path("title").asText(null)), companyName);
            appendIfRelevant(excerpt, sanitize(postData.path("selftext").asText(null)), companyName);

            JsonNode comments = root.path(1).path("data").path("children");
            int keptComments = 0;
            if (comments.isArray()) {
                for (JsonNode commentNode : comments) {
                    if (keptComments >= 3) {
                        break;
                    }
                    String body = sanitize(commentNode.path("data").path("body").asText(null));
                    if (body == null) {
                        continue;
                    }
                    appendIfRelevant(excerpt, body, companyName);
                    keptComments++;
                }
            }

            String text = trimExcerpt(excerpt.toString());
            return text == null ? null : new ScrapedContent("reddit-json", text);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private ScrapedContent scrapeHtmlPlatform(String url, String companyName, String evidenceType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            Document document = Jsoup.parse(response.body(), url);
            String excerpt = switch (evidenceType) {
                case "indeed-public-html" -> extractIndeedText(document, companyName);
                case "glassdoor-public-html" -> extractGlassdoorText(document, companyName);
                default -> extractGenericRelevantText(document, companyName);
            };

            excerpt = trimExcerpt(excerpt);
            return excerpt == null ? null : new ScrapedContent(evidenceType, excerpt);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private String extractIndeedText(Document document, String companyName) {
        StringBuilder builder = new StringBuilder();
        appendIfRelevant(builder, document.title(), companyName);
        appendIfRelevant(builder, document.select("meta[name=description]").attr("content"), companyName);

        Elements candidates = document.select(
                "[data-testid*=review], [class*=review], [class*=cmp-Review], [class*=css-][class*=review], article, p");
        appendElements(builder, candidates, companyName, 6);
        return builder.toString();
    }

    private String extractGlassdoorText(Document document, String companyName) {
        StringBuilder builder = new StringBuilder();
        appendIfRelevant(builder, document.title(), companyName);
        appendIfRelevant(builder, document.select("meta[name=description]").attr("content"), companyName);

        Elements candidates = document.select(
                "[data-test*=review], [class*=review], [class*=Review], article, p, li");
        appendElements(builder, candidates, companyName, 6);
        return builder.toString();
    }

    private String extractGenericRelevantText(Document document, String companyName) {
        StringBuilder builder = new StringBuilder();
        appendIfRelevant(builder, document.title(), companyName);
        appendIfRelevant(builder, document.select("meta[name=description]").attr("content"), companyName);
        appendElements(builder, document.select("p, li, article"), companyName, 5);
        return builder.toString();
    }

    private void appendElements(StringBuilder builder, Elements elements, String companyName, int maxItems) {
        int added = 0;
        for (Element element : elements) {
            if (added >= maxItems) {
                break;
            }
            String text = sanitize(element.text());
            if (text == null) {
                continue;
            }
            if (!looksRelevant(companyName, text) && !containsReviewSignal(text)) {
                continue;
            }
            appendLine(builder, text);
            added++;
        }
    }

    private ReviewVerificationResult analyzeEvidenceWithGemini(String companyName, EvidenceCollection evidenceCollection) {
        try {
            String prompt = buildPrompt(companyName, evidenceCollection.evidence());
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of(
                            "responseMimeType", "application/json",
                            "temperature", 0.1)
            ));

            String endpoint = String.format(GEMINI_API_URL_TEMPLATE, geminiModel)
                    + "?key=" + URLEncoder.encode(geminiApiKey, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode partsNode = objectMapper.readTree(response.body())
                    .path("candidates").path(0).path("content").path("parts");
            String payload = joinParts(partsNode);
            if (payload == null || payload.isBlank()) {
                return null;
            }

            String json = extractJsonObject(payload);
            if (json == null) {
                return null;
            }

            JsonNode root = objectMapper.readTree(json);
            int positiveCount = Math.max(0, root.path("positive_count").asInt(0));
            int negativeCount = Math.max(0, root.path("negative_count").asInt(0));
            double riskScore = clamp(root.path("risk_score").asDouble(0.0));
            String summary = sanitize(root.path("summary").asText(null));

            List<ScamReport> sources = new ArrayList<>();
            JsonNode sourcesNode = root.path("sources");
            if (sourcesNode.isArray()) {
                for (JsonNode node : sourcesNode) {
                    String title = sanitize(node.path("title").asText(null));
                    String link = sanitize(node.path("url").asText(null));
                    String platform = sanitize(node.path("platform").asText(null));
                    if (title != null && link != null && platform != null) {
                        sources.add(new ScamReport(title, "", link, platform));
                    }
                }
            }

            if (summary == null) {
                summary = "No review summary available.";
            }

            return new ReviewVerificationResult(
                    riskScore,
                    positiveCount,
                    negativeCount,
                    summary,
                    List.copyOf(sources),
                    evidenceCollection.evidenceTypes(),
                    buildEvidenceDetails(evidenceCollection.evidence()),
                    buildReviewFlags(evidenceCollection.evidence(), riskScore));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private String buildPrompt(String companyName, List<SearchEvidence> evidence) throws IOException {
        return """
                Analyze the following public review evidence for the company "%s".
                The evidence includes search results and, where technically practical, text scraped from public review pages.

                Return ONLY valid JSON in this exact shape:
                {
                  "positive_count": 0,
                  "negative_count": 0,
                  "risk_score": 0.0,
                  "summary": "Short summary",
                  "sources": [
                    {
                      "platform": "Reddit",
                      "title": "Title here",
                      "url": "https://..."
                    }
                  ]
                }

                Rules:
                - Count positive and negative evidence only from the evidence list below.
                - Prefer scraped page excerpts over plain search snippets when both exist.
                - Do not invent reviews, counts, or URLs.
                - Treat scam complaints, fake offer complaints, non-payment complaints, harassment complaints, and fraud allegations as negative.
                - Treat clearly positive work experience, legitimate company presence, and favorable employee feedback as positive.
                - If evidence is mixed, reflect that in counts and summary.
                - If evidence is weak or unclear, keep the counts low and the risk_score conservative.
                - risk_score must be between 0.0 and 1.0.
                - sources must include only links taken from the evidence list below.

                Evidence:
                %s
                """.formatted(companyName, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    private String buildRedditJsonUrl(String url) {
        if (url == null || !url.contains("reddit.com")) {
            return null;
        }
        String normalized = url.replace("://www.reddit.com", "://www.reddit.com")
                .replace("://old.reddit.com", "://www.reddit.com");
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith(".json") ? normalized : normalized + ".json";
    }

    private boolean looksRelevant(String companyName, String text) {
        String normalizedCompany = normalize(companyName);
        String normalizedText = normalize(text);
        if (normalizedCompany.isBlank() || normalizedText.isBlank()) {
            return false;
        }
        for (String token : normalizedCompany.split(" ")) {
            if (token.length() > 2 && normalizedText.contains(token)) {
                return true;
            }
        }
        return normalizedText.contains(normalizedCompany);
    }

    private boolean containsReviewSignal(String text) {
        String normalized = normalize(text);
        return normalized.contains("review")
                || normalized.contains("reviews")
                || normalized.contains("scam")
                || normalized.contains("fake offer")
                || normalized.contains("work culture")
                || normalized.contains("salary")
                || normalized.contains("interview")
                || normalized.contains("fraud");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private void appendIfRelevant(StringBuilder builder, String text, String companyName) {
        String cleaned = sanitize(text);
        if (cleaned == null) {
            return;
        }
        if (!looksRelevant(companyName, cleaned) && !containsReviewSignal(cleaned)) {
            return;
        }
        appendLine(builder, cleaned);
    }

    private void appendLine(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private String trimExcerpt(String text) {
        String cleaned = sanitize(text);
        if (cleaned == null) {
            return null;
        }
        return cleaned.length() <= MAX_EXCERPT_CHARS ? cleaned : cleaned.substring(0, MAX_EXCERPT_CHARS);
    }

    private String joinParts(JsonNode partsNode) {
        if (partsNode == null || !partsNode.isArray()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : partsNode) {
            String text = sanitize(part.path("text").asText(null));
            if (text == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(text);
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

    private String firstModel(String modelProperty) {
        if (modelProperty == null || modelProperty.isBlank()) {
            return "gemini-2.5-flash";
        }
        return modelProperty.split(",")[0].trim();
    }

    private ReviewVerificationResult heuristicFallback(EvidenceCollection evidenceCollection) {
        int positive = 0;
        int negative = 0;
        for (SearchEvidence item : evidenceCollection.evidence()) {
            String text = normalize(item.excerpt());
            if (containsAny(text, "scam", "fake", "fraud", "non payment", "did not pay", "warning", "complaint")) {
                negative++;
            }
            if (containsAny(text, "good company", "great place", "positive", "legit", "professional", "recommended")) {
                positive++;
            }
        }

        int totalSignals = positive + negative;
        double riskScore = totalSignals == 0 ? 0.0 : clamp((double) negative / totalSignals);
        String summary = totalSignals == 0
                ? "Review evidence was found, but Gemini verification failed. No strong positive or negative review signals were derived automatically."
                : "Gemini verification failed, so a heuristic review score was generated from the collected public review evidence.";

        List<ScamReport> sources = evidenceCollection.evidence().stream()
                .map(item -> new ScamReport(item.title(), "", item.url(), item.platform()))
                .distinct()
                .toList();

        return new ReviewVerificationResult(
                riskScore,
                positive,
                negative,
                summary,
                sources,
                evidenceCollection.evidenceTypes(),
                buildEvidenceDetails(evidenceCollection.evidence()),
                buildReviewFlags(evidenceCollection.evidence(), riskScore));
    }

    private ReviewVerificationResult emptyResult(String summary, Map<String, String> evidenceTypes) {
        return new ReviewVerificationResult(0.0, 0, 0, summary, List.of(), evidenceTypes, List.of(), List.of());
    }

    private String updatePlatformType(String current, String evidenceType) {
        String next = "search-snippet-fallback".equals(evidenceType) ? "Fallback" : "Scraped";
        if (current == null || current.equals("No Match") || current.equals("Search Failed") || current.equals("Unavailable")) {
            return next;
        }
        if (!current.equals(next)) {
            return "Mixed";
        }
        return current;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private List<ReviewEvidenceDebug> buildEvidenceDetails(List<SearchEvidence> evidence) {
        return evidence.stream()
                .map(item -> new ReviewEvidenceDebug(
                        item.platform(),
                        item.title(),
                        item.url(),
                        item.evidenceType(),
                        item.excerpt()))
                .toList();
    }

    private List<RedFlagCheck> buildReviewFlags(List<SearchEvidence> evidence, double riskScore) {
        List<SearchEvidence> negativeEvidence = evidence.stream()
                .filter(item -> containsNegativeReviewSignal(normalize(item.excerpt())))
                .limit(5)
                .toList();
        if (negativeEvidence.isEmpty()) {
            return List.of();
        }

        return negativeEvidence.stream()
                .map(item -> new RedFlagCheck(
                        item.platform() + " negative review evidence",
                        trimForDisplay(item.excerpt()),
                        "This public review/search evidence contains scam, fraud, warning, complaint, or non-payment language.",
                        item.url(),
                        SignalCategory.REVIEW,
                        null))
                .toList();
    }

    private boolean containsNegativeReviewSignal(String normalizedText) {
        return containsAny(normalizedText,
                "scam",
                "fake",
                "fraud",
                "non payment",
                "did not pay",
                "warning",
                "complaint",
                "complaints",
                "avoid",
                "bad experience");
    }

    private String trimForDisplay(String text) {
        String cleaned = sanitize(text);
        if (cleaned == null) {
            return "Review evidence matched risk keywords.";
        }
        return cleaned.length() <= 260 ? cleaned : cleaned.substring(0, 260) + "...";
    }

    private record PlatformQuery(String platform, String queryTemplate) {
    }

    private record SearchEvidence(
            String platform,
            String title,
            String snippet,
            String url,
            String evidenceType,
            String excerpt) {
    }

    private record ScrapedContent(String evidenceType, String excerpt) {
    }

    private record EvidenceCollection(List<SearchEvidence> evidence, Map<String, String> evidenceTypes) {
    }
}
