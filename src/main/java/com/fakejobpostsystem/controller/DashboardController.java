package com.fakejobpostsystem.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fakejobpostsystem.model.Prediction;
import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.dto.ForensicsResult;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.dto.ReviewVerificationResult;
import com.fakejobpostsystem.dto.Entities;
import com.fakejobpostsystem.repository.PredictionRepository;
import com.fakejobpostsystem.service.CurrentUserService;
import com.fakejobpostsystem.service.DetectionService;
import com.fakejobpostsystem.service.OutcomeReportService;

@Controller
@RequestMapping
public class DashboardController {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Map<String, String> DEFAULT_REVIEW_EVIDENCE_TYPES = Map.of(
            "Reddit", "Unavailable",
            "Indeed", "Unavailable",
            "Glassdoor", "Unavailable");

    private final PredictionRepository predictionRepository;
    private final CurrentUserService currentUserService;
    private final DetectionService detectionService;
    private final OutcomeReportService outcomeReportService;
    private final ObjectMapper objectMapper;

    public DashboardController(PredictionRepository predictionRepository, 
                               CurrentUserService currentUserService, 
                               DetectionService detectionService,
                               OutcomeReportService outcomeReportService,
                               ObjectMapper objectMapper) {
        this.predictionRepository = predictionRepository;
        this.currentUserService = currentUserService;
        this.detectionService = detectionService;
        this.outcomeReportService = outcomeReportService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication authentication, Model model) {
        User user = currentUserService.requireUser(authentication);
        if ("ROLE_ADMIN".equals(user.getRole())) {
            return "redirect:/admin/outcome-reports";
        }
        if ("ROLE_TPO".equals(user.getRole())) {
            return "redirect:/tpo/dashboard";
        }
        List<Prediction> predictions = predictionRepository.findTop10ByUser_IdOrderByTimestampDesc(user.getId());
        model.addAttribute("predictions", predictions);
        return "dashboard";
    }

    @PostMapping("/detect")
    public String detect(
            Authentication authentication,
            @RequestParam(name = "job_text", required = false) String jobText,
            @RequestParam(name = "job_image", required = false) MultipartFile jobImage,
            Model model,
            RedirectAttributes redirectAttributes) {
        try {
            boolean hasImage = jobImage != null && !jobImage.isEmpty();
            boolean hasText = jobText != null && !jobText.trim().isEmpty();
            if (!hasImage && !hasText) {
                redirectAttributes.addFlashAttribute("errorMessage", "No job description found");
                return "redirect:/dashboard";
            }

            var result = detectionService.analyze(jobText, jobImage);
            if (result.text() == null || result.text().isBlank()) {
                redirectAttributes.addFlashAttribute("errorMessage", "No job description found after OCR or text extraction");
                return "redirect:/dashboard";
            }

            User user = currentUserService.requireUser(authentication);
            ReviewVerificationResult reviewResult = ensureReviewResult(result.reviewVerification(), result.reviewRiskScore());

            Prediction prediction = new Prediction();
            prediction.setUser(user);
            prediction.setJobText(detectionService.preview(result.text()));
            prediction.setScore(result.score());
            prediction.setMlScore(result.mlScore());
            prediction.setReviewRiskScore(reviewResult.riskScore());
            prediction.setSourceType(result.sourceType());
            prediction.setImageFilename(result.imageFilename());
            prediction.setCompanyName(result.companyName());
            prediction.setWebsiteUrl(result.companyInfo().website());
            prediction.setLinkedinUrl(result.companyInfo().linkedin());

            if (result.forensicsResult() != null) {
                prediction.setForensicsScore(result.forensicsResult().forensicsScore());
                try {
                    prediction.setForensicsFlagsJson(objectMapper.writeValueAsString(result.forensicsResult().flags()));
                } catch (Exception e) {
                    System.err.println("Failed to serialize forensics data: " + e.getMessage());
                }
            }

            prediction.setReviewSummary(reviewResult.summary());
            prediction.setReviewPositiveCount(reviewResult.positiveCount());
            prediction.setReviewNegativeCount(reviewResult.negativeCount());
            try {
                prediction.setReviewSourcesJson(objectMapper.writeValueAsString(reviewResult.sources()));
                prediction.setReviewEvidenceTypesJson(objectMapper.writeValueAsString(reviewResult.evidenceTypes()));
            } catch (Exception e) {
                System.err.println("Failed to serialize review data: " + e.getMessage());
            }
            
            if (result.groqVerification() != null) {
                prediction.setGroqRiskScore(result.groqVerification().riskScore());
                prediction.setGroqSummary(result.groqVerification().summary());
                try {
                    prediction.setGroqRedFlagsJson(objectMapper.writeValueAsString(result.groqVerification().redFlags()));
                    prediction.setGroqScamReportsJson(objectMapper.writeValueAsString(result.groqVerification().scamReports()));
                } catch (Exception e) {
                    System.err.println("Failed to serialize Groq data: " + e.getMessage());
                }
            }

            try {
                prediction.setEvidenceFlagsJson(objectMapper.writeValueAsString(result.evidenceTrail()));
            } catch (Exception e) {
                System.err.println("Failed to serialize unified evidence data: " + e.getMessage());
            }
            
            predictionRepository.save(prediction);

            populateResultModel(model,
                    result.rawOcrText(),
                    result.score(),
                    result.mlScore(),
                    reviewResult.riskScore(),
                    result.entities(),
                    detectionService.preview(result.text()),
                    result.sourceType(),
                    result.companyInfo(),
                    result.forensicsResult(),
                    result.groqVerification(),
                    reviewResult,
                    result.outcomeWarning(),
                    result.evidenceTrail());
            return "result";
        } catch (Exception ex) {
            ex.printStackTrace();
            redirectAttributes.addFlashAttribute("errorMessage", "Detection failed: " + ex.getMessage());
            return "redirect:/dashboard";
        }
    }

    @GetMapping("/view-prediction/{predictionId}")
    public String viewPrediction(@PathVariable("predictionId") Long predictionId, Authentication authentication, Model model) {
        try {
            User user = currentUserService.requireUser(authentication);
            Prediction prediction = "ROLE_TPO".equals(user.getRole()) && user.getInstitution() != null
                    ? predictionRepository.findByIdAndInstitution_Id(predictionId, user.getInstitution().getId())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))
                    : predictionRepository.findByIdAndUser_Id(predictionId, user.getId())
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

            String previewText = prediction.getJobText() != null ? prediction.getJobText() : "No text available";

            populateResultModel(model,
                    null,
                    prediction.getScore(),
                    prediction.getMlScore(),
                    prediction.getReviewRiskScore(),
                    null,
                    previewText,
                    prediction.getSourceType() == null ? "text" : prediction.getSourceType(),
                    new com.fakejobpostsystem.dto.CompanyInfo(prediction.getCompanyName(), prediction.getWebsiteUrl(), prediction.getLinkedinUrl()),
                    restoreForensicsResult(prediction),
                    restoreGroqResult(prediction),
                    restoreReviewResult(prediction),
                    outcomeReportService.warningForCompany(prediction.getCompanyName()).orElse(null),
                    restoreEvidenceTrail(prediction));
            return "result";
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    @GetMapping("/public/prediction/{publicToken}")
    public String viewPublicPrediction(@PathVariable("publicToken") String publicToken, Model model) {
        Prediction prediction = predictionRepository.findByPublicToken(publicToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String previewText = prediction.getJobText() != null ? prediction.getJobText() : "No text available";
        populateResultModel(model,
                null,
                prediction.getScore(),
                prediction.getMlScore(),
                prediction.getReviewRiskScore(),
                null,
                previewText,
                prediction.getSourceType() == null ? "text" : prediction.getSourceType(),
                new com.fakejobpostsystem.dto.CompanyInfo(prediction.getCompanyName(), prediction.getWebsiteUrl(), prediction.getLinkedinUrl()),
                restoreForensicsResult(prediction),
                restoreGroqResult(prediction),
                restoreReviewResult(prediction),
                outcomeReportService.warningForCompany(prediction.getCompanyName()).orElse(null),
                restoreEvidenceTrail(prediction));
        return "result";
    }

    private List<RedFlagCheck> restoreEvidenceTrail(Prediction prediction) {
        if (prediction == null || prediction.getEvidenceFlagsJson() == null || prediction.getEvidenceFlagsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(prediction.getEvidenceFlagsJson(), new TypeReference<List<RedFlagCheck>>() {});
        } catch (Exception e) {
            System.err.println("Failed to restore unified evidence data for prediction " + prediction.getId() + ": " + e.getMessage());
            return List.of();
        }
    }

    private ForensicsResult restoreForensicsResult(Prediction prediction) {
        if (prediction == null || (prediction.getForensicsScore() == null && prediction.getForensicsFlagsJson() == null)) {
            return null;
        }
        try {
            String flagsJson = prediction.getForensicsFlagsJson();
            List<com.fakejobpostsystem.dto.RedFlagCheck> flags = (flagsJson == null || flagsJson.isBlank())
                    ? List.of()
                    : objectMapper.readValue(flagsJson, new TypeReference<List<com.fakejobpostsystem.dto.RedFlagCheck>>() {});
            return new ForensicsResult(
                    prediction.getForensicsScore() == null ? 0.0 : prediction.getForensicsScore(),
                    flags);
        } catch (Exception e) {
            System.err.println("Failed to restore forensics data for prediction " + prediction.getId() + ": " + e.getMessage());
            return new ForensicsResult(prediction.getForensicsScore() == null ? 0.0 : prediction.getForensicsScore(), List.of());
        }
    }

    private ReviewVerificationResult restoreReviewResult(Prediction prediction) {
        if (prediction == null) {
            return fallbackReviewResult(null, "Review verification data is unavailable for this prediction.");
        }
        if (prediction.getReviewSummary() == null && prediction.getReviewRiskScore() == null) {
            return fallbackReviewResult(null, "Review verification data is unavailable for this prediction.");
        }
        try {
            String sourcesJson = prediction.getReviewSourcesJson();
            List<com.fakejobpostsystem.dto.ScamReport> sources = (sourcesJson == null || sourcesJson.isBlank())
                    ? List.of()
                    : objectMapper.readValue(sourcesJson, new TypeReference<List<com.fakejobpostsystem.dto.ScamReport>>() {});
            String evidenceTypesJson = prediction.getReviewEvidenceTypesJson();
            Map<String, String> evidenceTypes = (evidenceTypesJson == null || evidenceTypesJson.isBlank())
                    ? Map.of()
                    : objectMapper.readValue(evidenceTypesJson, new TypeReference<Map<String, String>>() {});

            return new ReviewVerificationResult(
                    prediction.getReviewRiskScore() == null ? 0.0 : prediction.getReviewRiskScore(),
                    prediction.getReviewPositiveCount() == null ? 0 : prediction.getReviewPositiveCount(),
                    prediction.getReviewNegativeCount() == null ? 0 : prediction.getReviewNegativeCount(),
                    prediction.getReviewSummary() == null ? "Review verification data is unavailable for this prediction." : prediction.getReviewSummary(),
                    sources,
                    evidenceTypes.isEmpty() ? DEFAULT_REVIEW_EVIDENCE_TYPES : evidenceTypes,
                    List.of(),
                    List.of()
            );
        } catch (Exception e) {
            System.err.println("Failed to restore review data for prediction " + prediction.getId() + ": " + e.getMessage());
            return fallbackReviewResult(prediction.getReviewRiskScore(), "Review verification data could not be restored for this prediction.");
        }
    }

    private com.fakejobpostsystem.dto.GroqVerificationResult restoreGroqResult(Prediction prediction) {
        if (prediction == null || prediction.getGroqSummary() == null) {
            return null;
        }
        try {
            String redFlagsJson = prediction.getGroqRedFlagsJson();
            List<com.fakejobpostsystem.dto.RedFlagCheck> flags = (redFlagsJson == null || redFlagsJson.isBlank())
                ? List.of() 
                : objectMapper.readValue(redFlagsJson, new TypeReference<List<com.fakejobpostsystem.dto.RedFlagCheck>>() {});
            
            String reportsJson = prediction.getGroqScamReportsJson();
            List<com.fakejobpostsystem.dto.ScamReport> reports = (reportsJson == null || reportsJson.isBlank()) 
                ? List.of() 
                : objectMapper.readValue(reportsJson, new TypeReference<List<com.fakejobpostsystem.dto.ScamReport>>() {});
            
            return new com.fakejobpostsystem.dto.GroqVerificationResult(
                prediction.getGroqRiskScore() != null && prediction.getGroqRiskScore() < 0.5,
                prediction.getGroqRiskScore() == null ? 0.0 : prediction.getGroqRiskScore(),
                prediction.getGroqSummary(),
                flags,
                reports
            );
        } catch (Exception e) {
            System.err.println("Failed to restore Groq data for prediction " + prediction.getId() + ": " + e.getMessage());
            return null;
        }
    }

    @PostMapping("/delete-prediction/{predictionId}")
    public String deletePrediction(@PathVariable("predictionId") Long predictionId, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            User user = currentUserService.requireUser(authentication);
            Prediction prediction = predictionRepository.findByIdAndUser_Id(predictionId, user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            predictionRepository.delete(prediction);
            redirectAttributes.addFlashAttribute("successMessage", "Prediction deleted successfully");
            return "redirect:/dashboard";
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private void populateResultModel(
            Model model,
            String rawOcrText,
            Double score,
            Double mlScore,
            Double reviewRiskScore,
            Object entities,
            String jobText,
            String sourceType,
            com.fakejobpostsystem.dto.CompanyInfo companyInfo,
            ForensicsResult forensicsResult,
            com.fakejobpostsystem.dto.GroqVerificationResult groqResult,
            ReviewVerificationResult reviewResult,
            com.fakejobpostsystem.dto.OutcomeReputationWarning outcomeWarning,
            List<RedFlagCheck> evidenceTrail) {
        ReviewVerificationResult safeReviewResult = ensureReviewResult(reviewResult, reviewRiskScore);
        model.addAttribute("rawOcrText", rawOcrText);
        model.addAttribute("score", score);
        model.addAttribute("mlScore", mlScore);
        model.addAttribute("reviewRiskScore", safeReviewResult.riskScore());
        model.addAttribute("entities", entities);
        model.addAttribute("jobText", jobText);
        model.addAttribute("sourceType", sourceType);
        model.addAttribute("companyInfo", companyInfo);
        model.addAttribute("forensicsResult", forensicsResult);
        model.addAttribute("groqResult", groqResult);
        model.addAttribute("reviewResult", safeReviewResult);
        model.addAttribute("outcomeWarning", outcomeWarning);
        model.addAttribute("evidenceTrail", evidenceTrail == null ? List.of() : evidenceTrail);
        model.addAttribute("reportCompanyName", companyInfo == null ? null : companyInfo.company());
        model.addAttribute("reportCompanyEmailDomain", firstEmailDomain(entities));
        model.addAttribute("reportPhoneNumber", firstPhone(entities));
    }

    private String firstEmailDomain(Object entities) {
        if (!(entities instanceof Entities extracted) || extracted.emails() == null || extracted.emails().isEmpty()) {
            return null;
        }
        String email = extracted.emails().get(0);
        int atIndex = email == null ? -1 : email.indexOf('@');
        return atIndex >= 0 && atIndex < email.length() - 1 ? email.substring(atIndex + 1) : null;
    }

    private String firstPhone(Object entities) {
        if (!(entities instanceof Entities extracted) || extracted.phones() == null || extracted.phones().isEmpty()) {
            return null;
        }
        return extracted.phones().get(0);
    }

    private ReviewVerificationResult ensureReviewResult(ReviewVerificationResult reviewResult, Double reviewRiskScore) {
        if (reviewResult != null) {
            Map<String, String> evidenceTypes = reviewResult.evidenceTypes() == null || reviewResult.evidenceTypes().isEmpty()
                    ? DEFAULT_REVIEW_EVIDENCE_TYPES
                    : reviewResult.evidenceTypes();
            return new ReviewVerificationResult(
                    reviewResult.riskScore(),
                    reviewResult.positiveCount(),
                    reviewResult.negativeCount(),
                    reviewResult.summary() == null || reviewResult.summary().isBlank()
                            ? "Review verification completed without a summary."
                            : reviewResult.summary(),
                    reviewResult.sources() == null ? List.of() : reviewResult.sources(),
                    evidenceTypes,
                    reviewResult.evidenceDetails() == null ? List.of() : reviewResult.evidenceDetails(),
                    reviewResult.redFlags() == null ? List.of() : reviewResult.redFlags());
        }
        return fallbackReviewResult(reviewRiskScore, "Review verification was unavailable for this analysis.");
    }

    private ReviewVerificationResult fallbackReviewResult(Double reviewRiskScore, String summary) {
        return new ReviewVerificationResult(
                reviewRiskScore == null ? 0.0 : reviewRiskScore,
                0,
                0,
                summary,
                List.of(),
                DEFAULT_REVIEW_EVIDENCE_TYPES,
                List.of(),
                List.of());
    }

    public static String formatTimestamp(LocalDateTime timestamp) {
        return timestamp == null ? "N/A" : timestamp.format(TIMESTAMP_FORMATTER);
    }
}
