package com.fakejobpostsystem.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fakejobpostsystem.dto.DetectionResult;
import com.fakejobpostsystem.dto.ForensicsResult;
import com.fakejobpostsystem.dto.GroqVerificationResult;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.dto.ReviewVerificationResult;
import com.fakejobpostsystem.model.Prediction;
import com.fakejobpostsystem.repository.PredictionRepository;
import com.fakejobpostsystem.service.DetectionService;
import com.fakejobpostsystem.service.OcrService;
import com.fakejobpostsystem.service.WhatsAppCloudApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class WhatsAppWebhookController {

    // During Meta Cloud API development, WhatsApp messages work only for test recipients
    // manually added in the Meta App Dashboard. Meta currently allows up to 5 test
    // phone numbers there; this restriction goes away after Business Verification.
    private static final int MAX_MESSAGES_PER_HOUR = 10;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofHours(1);

    private final DetectionService detectionService;
    private final OcrService ocrService;
    private final WhatsAppCloudApiService whatsAppCloudApiService;
    private final PredictionRepository predictionRepository;
    private final ObjectMapper objectMapper;
    private final String verifyToken;
    private final String publicBaseUrl;
    private final Map<String, Deque<Instant>> rateLimitBuckets = new ConcurrentHashMap<>();

    public WhatsAppWebhookController(
            DetectionService detectionService,
            OcrService ocrService,
            WhatsAppCloudApiService whatsAppCloudApiService,
            PredictionRepository predictionRepository,
            ObjectMapper objectMapper,
            @Value("${whatsapp.verify-token:${WHATSAPP_VERIFY_TOKEN:}}") String verifyToken,
            @Value("${app.public-base-url:${APP_PUBLIC_BASE_URL:}}") String publicBaseUrl) {
        this.detectionService = detectionService;
        this.ocrService = ocrService;
        this.whatsAppCloudApiService = whatsAppCloudApiService;
        this.predictionRepository = predictionRepository;
        this.objectMapper = objectMapper;
        this.verifyToken = verifyToken == null ? "" : verifyToken.trim();
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    @GetMapping("/webhook/whatsapp")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && !verifyToken.isBlank() && verifyToken.equals(token)) {
            return ResponseEntity.ok(challenge == null ? "" : challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    @PostMapping("/webhook/whatsapp")
    public ResponseEntity<Void> receiveWebhook(@RequestBody String payload, HttpServletRequest request) {
        String baseUrl = resolveBaseUrl(request);
        CompletableFuture.runAsync(() -> processWebhookPayload(payload, baseUrl));
        return ResponseEntity.ok().build();
    }

    private void processWebhookPayload(String payload, String baseUrl) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode entries = root.path("entry");
            if (!entries.isArray()) {
                return;
            }

            for (JsonNode entry : entries) {
                JsonNode changes = entry.path("changes");
                if (!changes.isArray()) {
                    continue;
                }
                for (JsonNode change : changes) {
                    JsonNode messages = change.path("value").path("messages");
                    if (!messages.isArray()) {
                        continue;
                    }
                    for (JsonNode message : messages) {
                        processMessage(message, baseUrl);
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("Failed to process WhatsApp webhook payload: " + ex.getMessage());
        }
    }

    private void processMessage(JsonNode message, String baseUrl) {
        String sender = message.path("from").asText("");
        String messageId = message.path("id").asText("");
        String type = message.path("type").asText("");
        System.out.println("WhatsApp webhook message received: type=" + type + ", from=" + sender + ", id=" + messageId);
        if (sender.isBlank()) {
            return;
        }

        if (isRateLimited(sender)) {
            sendReplyQuietly(sender, "Rate limit reached. Please try again later. Current limit is 10 scam checks per hour.");
            return;
        }

        try {
            String text;
            String sourceType;
            if ("text".equals(type)) {
                text = message.path("text").path("body").asText("").trim();
                sourceType = "whatsapp_text";
            } else if ("image".equals(type)) {
                sendReplyQuietly(sender, "Image received. Reading the offer letter and checking risk now...");
                text = extractTextFromImageMessage(message);
                sourceType = "whatsapp_image";
            } else if ("document".equals(type) && isImageDocument(message)) {
                sendReplyQuietly(sender, "Image document received. Reading it and checking risk now...");
                text = extractTextFromDocumentMessage(message);
                sourceType = "whatsapp_image";
            } else {
                sendReplyQuietly(sender, "Please send a suspicious job message as text or an offer-letter image/photo.");
                return;
            }

            if (text == null || text.isBlank()) {
                sendReplyQuietly(sender, "I could not read enough job text from that message. Please send a clearer image or paste the text.");
                return;
            }

            DetectionResult result = detectionService.analyze(text, null);
            Prediction prediction = saveWhatsAppPrediction(result, sender, messageId, sourceType);
            String resultLink = baseUrl + "/public/prediction/" + prediction.getPublicToken();
            sendReplyQuietly(sender, buildReply(result, resultLink));
        } catch (Exception ex) {
            System.err.println("WhatsApp analysis failed: " + ex.getMessage());
            sendReplyQuietly(sender, "Sorry, the scam check failed while analyzing this message. Please try again in a few minutes.");
        }
    }

    private String extractTextFromImageMessage(JsonNode message) throws Exception {
        String mediaId = message.path("image").path("id").asText("");
        return extractTextFromMedia(mediaId);
    }

    private String extractTextFromDocumentMessage(JsonNode message) throws Exception {
        String mediaId = message.path("document").path("id").asText("");
        return extractTextFromMedia(mediaId);
    }

    private String extractTextFromMedia(String mediaId) throws Exception {
        Path mediaPath = null;
        try {
            System.out.println("Downloading WhatsApp media id=" + mediaId);
            mediaPath = whatsAppCloudApiService.downloadMedia(mediaId);
            long bytes = Files.size(mediaPath);
            System.out.println("WhatsApp media downloaded: " + mediaPath + " (" + bytes + " bytes)");
            String extractedText = ocrService.extractText(mediaPath).trim();
            System.out.println("WhatsApp OCR extracted characters: " + extractedText.length());
            return extractedText;
        } finally {
            if (mediaPath != null) {
                Files.deleteIfExists(mediaPath);
            }
        }
    }

    private boolean isImageDocument(JsonNode message) {
        String mimeType = message.path("document").path("mime_type").asText("").toLowerCase();
        return mimeType.startsWith("image/");
    }

    private Prediction saveWhatsAppPrediction(
            DetectionResult result,
            String sender,
            String messageId,
            String sourceType) throws Exception {
        ReviewVerificationResult reviewResult = result.reviewVerification();
        GroqVerificationResult groqResult = result.groqVerification();
        ForensicsResult forensicsResult = result.forensicsResult();

        Prediction prediction = new Prediction();
        prediction.setPublicToken(UUID.randomUUID().toString());
        prediction.setWhatsappSenderNumber(sender);
        prediction.setWhatsappMessageId(messageId);
        prediction.setJobText(detectionService.preview(result.text()));
        prediction.setScore(result.score());
        prediction.setMlScore(result.mlScore());
        prediction.setReviewRiskScore(result.reviewRiskScore());
        prediction.setSourceType(sourceType);
        prediction.setCompanyName(result.companyName());
        prediction.setWebsiteUrl(result.companyInfo().website());
        prediction.setLinkedinUrl(result.companyInfo().linkedin());

        if (forensicsResult != null) {
            prediction.setForensicsScore(forensicsResult.forensicsScore());
            prediction.setForensicsFlagsJson(objectMapper.writeValueAsString(forensicsResult.flags()));
        }

        if (reviewResult != null) {
            prediction.setReviewSummary(reviewResult.summary());
            prediction.setReviewPositiveCount(reviewResult.positiveCount());
            prediction.setReviewNegativeCount(reviewResult.negativeCount());
            prediction.setReviewSourcesJson(objectMapper.writeValueAsString(reviewResult.sources()));
            prediction.setReviewEvidenceTypesJson(objectMapper.writeValueAsString(reviewResult.evidenceTypes()));
        }

        if (groqResult != null) {
            prediction.setGroqRiskScore(groqResult.riskScore());
            prediction.setGroqSummary(groqResult.summary());
            prediction.setGroqRedFlagsJson(objectMapper.writeValueAsString(groqResult.redFlags()));
            prediction.setGroqScamReportsJson(objectMapper.writeValueAsString(groqResult.scamReports()));
        }

        prediction.setEvidenceFlagsJson(objectMapper.writeValueAsString(result.evidenceTrail()));

        return predictionRepository.save(prediction);
    }

    private String buildReply(DetectionResult result, String resultLink) {
        double score = result.score();
        String verdict = score < 0.3 ? "Low" : score < 0.7 ? "Medium" : "High";
        StringBuilder reply = new StringBuilder();
        reply.append("Risk: ").append(verdict).append(" (")
                .append(String.format("%.1f", score * 100)).append("%)\n");
        if (result.companyName() != null && !result.companyName().isBlank()) {
            reply.append("Company: ").append(result.companyName()).append("\n");
        }
        reply.append("Top flags:\n");
        List<String> flags = topFlags(result);
        for (String flag : flags) {
            reply.append("- ").append(flag).append("\n");
        }
        reply.append("Full result: ").append(resultLink);
        return reply.toString();
    }

    private List<String> topFlags(DetectionResult result) {
        GroqVerificationResult groqResult = result.groqVerification();
        if (groqResult != null && groqResult.redFlags() != null && !groqResult.redFlags().isEmpty()) {
            return groqResult.redFlags().stream()
                    .map(RedFlagCheck::flag)
                    .filter(flag -> flag != null && !flag.isBlank())
                    .limit(3)
                    .toList();
        }

        ForensicsResult forensicsResult = result.forensicsResult();
        if (forensicsResult != null && forensicsResult.flags() != null && !forensicsResult.flags().isEmpty()) {
            return forensicsResult.flags().stream()
                    .map(RedFlagCheck::flag)
                    .filter(flag -> flag != null && !flag.isBlank())
                    .limit(3)
                    .toList();
        }

        ReviewVerificationResult reviewResult = result.reviewVerification();
        if (reviewResult != null && reviewResult.negativeCount() > 0) {
            return List.of(
                    "Negative public review signals found: " + reviewResult.negativeCount(),
                    "Verify the company website and LinkedIn before responding");
        }

        if (result.companyName() == null || result.companyName().isBlank()) {
            return List.of("Company name could not be identified", "Verify sender email and official career page manually");
        }

        return List.of("No strong red flags found", "Still verify the company before sharing documents or paying money");
    }

    private boolean isRateLimited(String sender) {
        Instant now = Instant.now();
        Deque<Instant> bucket = rateLimitBuckets.computeIfAbsent(sender, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().plus(RATE_LIMIT_WINDOW).isBefore(now)) {
                bucket.removeFirst();
            }
            if (bucket.size() >= MAX_MESSAGES_PER_HOUR) {
                return true;
            }
            bucket.addLast(now);
            return false;
        }
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (!publicBaseUrl.isBlank()) {
            return trimTrailingSlash(publicBaseUrl);
        }
        return trimTrailingSlash(ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString());
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void sendReplyQuietly(String to, String body) {
        try {
            whatsAppCloudApiService.sendTextMessage(to, body);
        } catch (Exception ex) {
            System.err.println("Failed to send WhatsApp reply: " + ex.getMessage());
        }
    }
}
