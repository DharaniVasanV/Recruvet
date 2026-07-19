package com.fakejobpostsystem.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "company_reputation_aggregate")
public class CompanyReputationAggregate {

    @Id
    @Column(name = "company_identifier", nullable = false, length = 255)
    private String companyIdentifier;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "scam_report_count", nullable = false)
    private long scamReportCount;

    @Column(name = "legit_report_count", nullable = false)
    private long legitReportCount;

    @Column(name = "manual_review_required", nullable = false)
    private boolean manualReviewRequired;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    public String getCompanyIdentifier() {
        return companyIdentifier;
    }

    public void setCompanyIdentifier(String companyIdentifier) {
        this.companyIdentifier = companyIdentifier;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public long getScamReportCount() {
        return scamReportCount;
    }

    public void setScamReportCount(long scamReportCount) {
        this.scamReportCount = scamReportCount;
    }

    public long getLegitReportCount() {
        return legitReportCount;
    }

    public void setLegitReportCount(long legitReportCount) {
        this.legitReportCount = legitReportCount;
    }

    public boolean isManualReviewRequired() {
        return manualReviewRequired;
    }

    public void setManualReviewRequired(boolean manualReviewRequired) {
        this.manualReviewRequired = manualReviewRequired;
    }

    public LocalDateTime getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(LocalDateTime lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
