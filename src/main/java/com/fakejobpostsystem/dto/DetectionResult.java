package com.fakejobpostsystem.dto;

import java.util.List;

public record DetectionResult(
        String text,
        String rawOcrText,
        double score,
        double mlScore,
        Double reviewRiskScore,
        Entities entities,
        String sourceType,
        String imageFilename,
        String companyName,
        CompanyInfo companyInfo,
        ForensicsResult forensicsResult,
        GroqVerificationResult groqVerification,
        ReviewVerificationResult reviewVerification,
        OutcomeReputationWarning outcomeWarning,
        List<RedFlagCheck> evidenceTrail
) {
}
