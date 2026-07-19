package com.fakejobpostsystem.dto;

import java.util.Map;
import java.util.List;

public record ReviewVerificationResult(
        double riskScore,
        int positiveCount,
        int negativeCount,
        String summary,
        List<ScamReport> sources,
        Map<String, String> evidenceTypes,
        List<ReviewEvidenceDebug> evidenceDetails,
        List<RedFlagCheck> redFlags
) {
}
