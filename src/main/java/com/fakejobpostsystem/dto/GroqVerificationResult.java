package com.fakejobpostsystem.dto;

import java.util.List;

public record GroqVerificationResult(
        boolean isLegit,
        double riskScore,
        String summary,
        List<RedFlagCheck> redFlags,
        List<ScamReport> scamReports
) {
}
