package com.fakejobpostsystem.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fakejobpostsystem.dto.CompanyInfo;
import com.fakejobpostsystem.dto.DetectionResult;
import com.fakejobpostsystem.dto.Entities;
import com.fakejobpostsystem.dto.ForensicsResult;
import com.fakejobpostsystem.dto.GroqVerificationResult;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.dto.RedFlagCheck.SignalCategory;
import com.fakejobpostsystem.dto.ReviewVerificationResult;

@Service
public class DetectionService {

    private static final long MAX_PREVIEW_LENGTH = 500;

    private final FileStorageService fileStorageService;
    private final OcrService ocrService;
    private final PdfTextExtractionService pdfTextExtractionService;
    private final MlInferenceService mlInferenceService;
    private final EntityExtractionService entityExtractionService;
    private final GeminiEntityCleanupService geminiEntityCleanupService;
    private final CompanyLookupService companyLookupService;
    private final GroqService groqService;
    private final ReviewVerificationService reviewVerificationService;
    private final DocumentForensicsService documentForensicsService;
    private final DomainAgeCheckService domainAgeCheckService;
    private final OutcomeReportService outcomeReportService;

    public DetectionService(
            FileStorageService fileStorageService,
            OcrService ocrService,
            PdfTextExtractionService pdfTextExtractionService,
            MlInferenceService mlInferenceService,
            EntityExtractionService entityExtractionService,
            GeminiEntityCleanupService geminiEntityCleanupService,
            CompanyLookupService companyLookupService,
            GroqService groqService,
            ReviewVerificationService reviewVerificationService,
            DocumentForensicsService documentForensicsService,
            DomainAgeCheckService domainAgeCheckService,
            OutcomeReportService outcomeReportService) {
        this.fileStorageService = fileStorageService;
        this.ocrService = ocrService;
        this.pdfTextExtractionService = pdfTextExtractionService;
        this.mlInferenceService = mlInferenceService;
        this.entityExtractionService = entityExtractionService;
        this.geminiEntityCleanupService = geminiEntityCleanupService;
        this.companyLookupService = companyLookupService;
        this.groqService = groqService;
        this.reviewVerificationService = reviewVerificationService;
        this.documentForensicsService = documentForensicsService;
        this.domainAgeCheckService = domainAgeCheckService;
        this.outcomeReportService = outcomeReportService;
    }

    public DetectionResult analyze(String jobText, MultipartFile jobImage) throws Exception {
        String sourceType = "text";
        String imageFilename = null;
        String text = jobText == null ? "" : jobText.trim();
        String rawOcrText = null;
        Path sourcePath = null;

        if (jobImage != null && !jobImage.isEmpty()) {
            imageFilename = fileStorageService.store(jobImage);
            sourcePath = fileStorageService.resolve(imageFilename);
            String originalName = jobImage.getOriginalFilename();
            String lowerName = originalName != null ? originalName.toLowerCase() : "";
            
            if (lowerName.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(jobImage.getContentType())) {
                text = pdfTextExtractionService.extractText(sourcePath).trim();
                sourceType = "pdf";
            } else {
                rawOcrText = ocrService.extractText(sourcePath).trim();
                text = rawOcrText;
                sourceType = "image";
            }
        }

        double mlScore = mlInferenceService.scoreText(text);
        double score = clamp(mlScore);

        Entities entities = entityExtractionService.extract(text);
        String companyName = entityExtractionService.inferCompanyNameFromEmails(entities.emails());
        if (companyName == null || companyName.isBlank()) {
            companyName = entityExtractionService.inferCompanyNameFromText(text, entities);
        }

        GeminiEntityCleanupService.EntityCleanupResult aiCleanup =
                geminiEntityCleanupService.cleanup(text, entities, companyName);
        if (aiCleanup != null) {
            entities = mergeEntities(entities, aiCleanup.entities(), true);
            if (aiCleanup.companyName() != null && !aiCleanup.companyName().isBlank()) {
                companyName = aiCleanup.companyName();
            } else {
                String mergedEmailCompany = entityExtractionService.inferCompanyNameFromEmails(entities.emails());
                if (mergedEmailCompany != null && !mergedEmailCompany.isBlank()) {
                    companyName = mergedEmailCompany;
                } else {
                    String mergedTextCompany = entityExtractionService.inferCompanyNameFromText(text, entities);
                    if (mergedTextCompany != null && !mergedTextCompany.isBlank()) {
                        companyName = mergedTextCompany;
                    }
                }
            }
        }

        companyName = entityExtractionService.normalizeCompanyDisplayName(companyName);

        ForensicsResult forensicsResult = isDocumentSource(sourceType)
                ? documentForensicsService.analyze(text, sourcePath, sourceType, entities)
                : null;
        CompanyInfo companyInfo = companyLookupService.lookup(companyName, text);
        ReviewVerificationResult reviewResult = reviewVerificationService.verify(companyName);
        Double reviewRiskScore = reviewResult == null ? null : reviewResult.riskScore();
        
        GroqVerificationResult groqResult = groqService.verify(companyName, text);
        score = weightedScore(mlScore, reviewRiskScore, groqResult == null ? null : groqResult.riskScore());
        var outcomeWarning = outcomeReportService.warningForCompany(companyName).orElse(null);
        List<RedFlagCheck> evidenceTrail = buildEvidenceTrail(
                mlScore,
                forensicsResult,
                companyInfo,
                entities,
                companyName,
                text,
                reviewResult,
                groqResult);

        return new DetectionResult(
                text,
                rawOcrText,
                score,
                mlScore,
                reviewRiskScore,
                entities,
                sourceType,
                imageFilename,
                companyName,
                companyInfo,
                forensicsResult,
                groqResult,
                reviewResult,
                outcomeWarning,
                evidenceTrail
        );
    }

    public String preview(String text) {
        return limit(text, MAX_PREVIEW_LENGTH);
    }

    private String limit(String text, long length) {
        if (text == null) {
            return "";
        }
        return text.length() <= length ? text : text.substring(0, (int) length);
    }

    private Entities mergeEntities(Entities heuristic, Entities ai, boolean aiOwnsPersons) {
        if (ai == null) {
            return heuristic;
        }
        return new Entities(
                preferred(ai.phones(), heuristic.phones()),
                preferred(ai.emails(), heuristic.emails()),
                aiOwnsPersons ? safe(ai.persons()) : preferred(ai.persons(), heuristic.persons()),
                preferred(ai.organizations(), heuristic.organizations())
        );
    }

    private java.util.List<String> preferred(java.util.List<String> ai, java.util.List<String> heuristic) {
        return ai != null && !ai.isEmpty() ? ai : heuristic;
    }

    private java.util.List<String> safe(java.util.List<String> values) {
        return values == null ? java.util.List.of() : values;
    }

    private double weightedScore(double mlScore, Double reviewRiskScore, Double aiRiskScore) {
        double totalWeight = 0.0;
        double weightedScore = 0.0;

        weightedScore += mlScore * 0.4;
        totalWeight += 0.4;

        if (reviewRiskScore != null) {
            weightedScore += reviewRiskScore * 0.4;
            totalWeight += 0.4;
        }
        if (aiRiskScore != null) {
            weightedScore += aiRiskScore * 0.2;
            totalWeight += 0.2;
        }

        if (totalWeight == 0.0) {
            return clamp(mlScore);
        }
        return clamp(weightedScore / totalWeight);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private boolean isDocumentSource(String sourceType) {
        return "pdf".equalsIgnoreCase(sourceType) || "image".equalsIgnoreCase(sourceType);
    }

    private List<RedFlagCheck> buildEvidenceTrail(
            double mlScore,
            ForensicsResult forensicsResult,
            CompanyInfo companyInfo,
            Entities entities,
            String companyName,
            String text,
            ReviewVerificationResult reviewResult,
            GroqVerificationResult groqResult) {
        List<RedFlagCheck> flags = new ArrayList<>();

        flags.add(new RedFlagCheck(
                "ML text-risk score",
                "The trained text classifier returned " + formatPercent(mlScore) + ".",
                "This model compares the submitted text with learned fake-job language patterns.",
                null,
                SignalCategory.ML,
                mlScore * 0.4));

        if (forensicsResult != null && forensicsResult.flags() != null) {
            for (RedFlagCheck flag : forensicsResult.flags()) {
                flags.add(withDefaults(flag, SignalCategory.FORENSICS, null));
            }
        }

        domainAgeCheckService.check(companyInfo, entities).ifPresent(flags::add);
        companyLookupService.employeeMismatchFlag(companyName, text).ifPresent(flags::add);

        if (reviewResult != null) {
            String sourceUrl = reviewResult.sources() == null || reviewResult.sources().isEmpty()
                    ? null
                    : reviewResult.sources().get(0).url();
            flags.add(new RedFlagCheck(
                    "Review reputation score",
                    "Review analysis found " + reviewResult.negativeCount() + " negative and " + reviewResult.positiveCount() + " positive public signals.",
                    reviewResult.summary(),
                    sourceUrl,
                    SignalCategory.REVIEW,
                    reviewResult.riskScore() * 0.4));
            if (reviewResult.redFlags() != null) {
                flags.addAll(reviewResult.redFlags());
            }
        }

        if (groqResult != null) {
            flags.add(new RedFlagCheck(
                    "AI verification score",
                    "Groq verification returned " + formatPercent(groqResult.riskScore()) + ".",
                    groqResult.summary(),
                    firstGroqSource(groqResult),
                    SignalCategory.GROQ_AI,
                    groqResult.riskScore() * 0.2));
            if (groqResult.redFlags() != null) {
                for (RedFlagCheck flag : groqResult.redFlags()) {
                    flags.add(withDefaults(flag, SignalCategory.GROQ_AI, null));
                }
            }
        }

        return List.copyOf(flags);
    }

    private String firstGroqSource(GroqVerificationResult groqResult) {
        if (groqResult.scamReports() == null || groqResult.scamReports().isEmpty()) {
            return null;
        }
        String url = groqResult.scamReports().get(0).url();
        return url == null || url.isBlank() || "#".equals(url) ? null : url;
    }

    private RedFlagCheck withDefaults(RedFlagCheck flag, SignalCategory category, Double contribution) {
        return new RedFlagCheck(
                flag.flag(),
                flag.evidence(),
                flag.explanation(),
                flag.sourceUrl(),
                flag.signalCategory() == null ? category : flag.signalCategory(),
                flag.weightContribution() == null ? contribution : flag.weightContribution());
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", value * 100.0);
    }
}
