package com.fakejobpostsystem.dto;

public record OutcomeReputationWarning(
        String companyName,
        long scamReportCount,
        long legitReportCount,
        long distinctScamReporterCount,
        String message
) {
}
