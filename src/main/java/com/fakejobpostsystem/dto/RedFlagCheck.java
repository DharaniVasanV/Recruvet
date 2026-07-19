package com.fakejobpostsystem.dto;

public record RedFlagCheck(
        String flag,
        String evidence,
        String explanation,
        String sourceUrl,
        SignalCategory signalCategory,
        Double weightContribution
) {
    public RedFlagCheck(String flag, String evidence, String explanation) {
        this(flag, evidence, explanation, null, null, null);
    }

    public enum SignalCategory {
        ML,
        FORENSICS,
        DOMAIN_AGE,
        REVIEW,
        GROQ_AI,
        EMPLOYEE_MISMATCH
    }
}
