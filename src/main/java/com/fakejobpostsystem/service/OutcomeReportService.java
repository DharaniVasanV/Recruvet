package com.fakejobpostsystem.service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fakejobpostsystem.dto.OutcomeReputationWarning;
import com.fakejobpostsystem.model.CompanyReputationAggregate;
import com.fakejobpostsystem.model.OutcomeReport;
import com.fakejobpostsystem.model.OutcomeReport.ConfirmedStatus;
import com.fakejobpostsystem.model.OutcomeReport.ModerationStatus;
import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.repository.CompanyReputationAggregateRepository;
import com.fakejobpostsystem.repository.OutcomeReportRepository;

@Service
public class OutcomeReportService {

    private static final long MIN_DISTINCT_SCAM_USERS = 3;
    private static final long SPIKE_REPORT_THRESHOLD = 5;

    private final OutcomeReportRepository outcomeReportRepository;
    private final CompanyReputationAggregateRepository aggregateRepository;
    private final EntityExtractionService entityExtractionService;

    public OutcomeReportService(
            OutcomeReportRepository outcomeReportRepository,
            CompanyReputationAggregateRepository aggregateRepository,
            EntityExtractionService entityExtractionService) {
        this.outcomeReportRepository = outcomeReportRepository;
        this.aggregateRepository = aggregateRepository;
        this.entityExtractionService = entityExtractionService;
    }

    @Transactional
    public OutcomeReport submitReport(
            User user,
            String companyName,
            String companyEmailDomain,
            String phoneNumber,
            ConfirmedStatus confirmedStatus,
            String evidenceText) {
        String normalizedCompany = entityExtractionService.normalizeCompanyDisplayName(companyName);
        if (normalizedCompany == null || normalizedCompany.isBlank()) {
            throw new IllegalArgumentException("Company name is required");
        }

        OutcomeReport report = new OutcomeReport();
        report.setUser(user);
        report.setCompanyName(normalizedCompany);
        report.setCompanyIdentifier(normalizeCompanyIdentifier(normalizedCompany));
        report.setCompanyEmailDomain(clean(companyEmailDomain, 255));
        report.setPhoneNumber(clean(phoneNumber, 60));
        report.setConfirmedStatus(confirmedStatus == null ? ConfirmedStatus.UNSURE : confirmedStatus);
        report.setEvidenceText(clean(evidenceText, 4000));
        report.setModerationStatus(ModerationStatus.PENDING);
        return outcomeReportRepository.save(report);
    }

    @Transactional
    public void moderate(Long reportId, ModerationStatus moderationStatus) {
        OutcomeReport report = outcomeReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Outcome report not found"));
        report.setModerationStatus(moderationStatus);
        outcomeReportRepository.save(report);
        recomputeAggregate(report.getCompanyIdentifier());
    }

    @Scheduled(fixedDelayString = "${outcome-reputation.recompute-delay-ms:900000}")
    @Transactional
    public void recomputeAggregates() {
        for (String identifier : outcomeReportRepository.findDistinctCompanyIdentifiersByModerationStatus(ModerationStatus.APPROVED)) {
            recomputeAggregate(identifier);
        }
    }

    @Transactional
    public void recomputeAggregate(String companyIdentifier) {
        if (companyIdentifier == null || companyIdentifier.isBlank()) {
            return;
        }

        long scamCount = outcomeReportRepository.countByCompanyIdentifierAndModerationStatusAndConfirmedStatus(
                companyIdentifier, ModerationStatus.APPROVED, ConfirmedStatus.CONFIRMED_SCAM);
        long legitCount = outcomeReportRepository.countByCompanyIdentifierAndModerationStatusAndConfirmedStatus(
                companyIdentifier, ModerationStatus.APPROVED, ConfirmedStatus.CONFIRMED_LEGITIMATE);
        long recentReports = outcomeReportRepository.countByCompanyIdentifierAndSubmittedAtAfter(
                companyIdentifier, LocalDateTime.now().minusHours(1));

        CompanyReputationAggregate aggregate = aggregateRepository.findById(companyIdentifier)
                .orElseGet(CompanyReputationAggregate::new);
        aggregate.setCompanyIdentifier(companyIdentifier);
        aggregate.setCompanyName(outcomeReportRepository.findTopByCompanyIdentifierOrderBySubmittedAtDesc(companyIdentifier)
                .map(OutcomeReport::getCompanyName)
                .orElse(companyIdentifier));
        aggregate.setScamReportCount(scamCount);
        aggregate.setLegitReportCount(legitCount);
        aggregate.setManualReviewRequired(recentReports >= SPIKE_REPORT_THRESHOLD);
        aggregate.setLastUpdated(LocalDateTime.now());
        aggregateRepository.save(aggregate);
    }

    @Transactional(readOnly = true)
    public Optional<OutcomeReputationWarning> warningForCompany(String companyName) {
        String identifier = normalizeCompanyIdentifier(companyName);
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }

        CompanyReputationAggregate aggregate = aggregateRepository.findById(identifier).orElse(null);
        if (aggregate == null || aggregate.isManualReviewRequired()) {
            return Optional.empty();
        }

        long distinctUsers = outcomeReportRepository.countDistinctUsersByCompanyAndStatus(
                identifier, ModerationStatus.APPROVED, ConfirmedStatus.CONFIRMED_SCAM);
        if (distinctUsers < MIN_DISTINCT_SCAM_USERS || aggregate.getScamReportCount() < MIN_DISTINCT_SCAM_USERS) {
            return Optional.empty();
        }

        String displayName = aggregate.getCompanyName() == null || aggregate.getCompanyName().isBlank()
                ? companyName
                : aggregate.getCompanyName();
        String message = "This company has " + aggregate.getScamReportCount()
                + " approved confirmed-scam reports from " + distinctUsers
                + " distinct users in the community database.";
        return Optional.of(new OutcomeReputationWarning(
                displayName,
                aggregate.getScamReportCount(),
                aggregate.getLegitReportCount(),
                distinctUsers,
                message));
    }

    public String normalizeCompanyIdentifier(String companyName) {
        String normalized = entityExtractionService.normalizeCompanyDisplayName(companyName);
        if (normalized == null) {
            return "";
        }
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", "-");
    }

    private String clean(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }
}
