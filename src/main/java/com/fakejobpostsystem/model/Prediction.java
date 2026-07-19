package com.fakejobpostsystem.model;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "predictions")
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "institution_id")
    private Institution institution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_job_id")
    private BatchJob batchJob;

    @Column(name = "job_text", nullable = false, columnDefinition = "TEXT")
    private String jobText;

    @Column(name = "score", nullable = false)
    private Double score;

    @Column(name = "ml_score")
    private Double mlScore;

    @Column(name = "review_risk_score")
    private Double reviewRiskScore;

    @Column(name = "review_summary", columnDefinition = "TEXT")
    private String reviewSummary;

    @Column(name = "review_positive_count")
    private Integer reviewPositiveCount;

    @Column(name = "review_negative_count")
    private Integer reviewNegativeCount;

    @Column(name = "review_sources_json", columnDefinition = "TEXT")
    private String reviewSourcesJson;

    @Column(name = "review_evidence_types_json", columnDefinition = "TEXT")
    private String reviewEvidenceTypesJson;

    @Column(name = "forensics_score")
    private Double forensicsScore;

    @Column(name = "forensics_flags_json", columnDefinition = "TEXT")
    private String forensicsFlagsJson;

    @Column(name = "groq_risk_score")
    private Double groqRiskScore;

    @Column(name = "groq_summary", columnDefinition = "TEXT")
    private String groqSummary;

    @Column(name = "groq_red_flags_json", columnDefinition = "TEXT")
    private String groqRedFlagsJson;

    @Column(name = "groq_scam_reports_json", columnDefinition = "TEXT")
    private String groqScamReportsJson;

    @Column(name = "evidence_flags_json", columnDefinition = "TEXT")
    private String evidenceFlagsJson;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "image_filename", length = 255)
    private String imageFilename;

    @Column(name = "company_name", length = 255)
    private String companyName;

    @Column(name = "website_url", length = 500)
    private String websiteUrl;

    @Column(name = "linkedin_url", length = 500)
    private String linkedinUrl;

    @Column(name = "whatsapp_sender_number", length = 40)
    private String whatsappSenderNumber;

    @Column(name = "whatsapp_message_id", length = 150)
    private String whatsappMessageId;

    @Column(name = "public_token", unique = true, length = 64)
    private String publicToken;

    @PrePersist
    public void prePersist() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
        if (publicToken == null || publicToken.isBlank()) {
            publicToken = UUID.randomUUID().toString();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Institution getInstitution() {
        return institution;
    }

    public void setInstitution(Institution institution) {
        this.institution = institution;
    }

    public BatchJob getBatchJob() {
        return batchJob;
    }

    public void setBatchJob(BatchJob batchJob) {
        this.batchJob = batchJob;
    }

    public String getJobText() {
        return jobText;
    }

    public void setJobText(String jobText) {
        this.jobText = jobText;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public Double getMlScore() {
        return mlScore;
    }

    public void setMlScore(Double mlScore) {
        this.mlScore = mlScore;
    }

    public Double getReviewRiskScore() {
        return reviewRiskScore;
    }

    public void setReviewRiskScore(Double reviewRiskScore) {
        this.reviewRiskScore = reviewRiskScore;
    }

    public String getReviewSummary() {
        return reviewSummary;
    }

    public void setReviewSummary(String reviewSummary) {
        this.reviewSummary = reviewSummary;
    }

    public Integer getReviewPositiveCount() {
        return reviewPositiveCount;
    }

    public void setReviewPositiveCount(Integer reviewPositiveCount) {
        this.reviewPositiveCount = reviewPositiveCount;
    }

    public Integer getReviewNegativeCount() {
        return reviewNegativeCount;
    }

    public void setReviewNegativeCount(Integer reviewNegativeCount) {
        this.reviewNegativeCount = reviewNegativeCount;
    }

    public String getReviewSourcesJson() {
        return reviewSourcesJson;
    }

    public void setReviewSourcesJson(String reviewSourcesJson) {
        this.reviewSourcesJson = reviewSourcesJson;
    }

    public String getReviewEvidenceTypesJson() {
        return reviewEvidenceTypesJson;
    }

    public void setReviewEvidenceTypesJson(String reviewEvidenceTypesJson) {
        this.reviewEvidenceTypesJson = reviewEvidenceTypesJson;
    }

    public Double getForensicsScore() {
        return forensicsScore;
    }

    public void setForensicsScore(Double forensicsScore) {
        this.forensicsScore = forensicsScore;
    }

    public String getForensicsFlagsJson() {
        return forensicsFlagsJson;
    }

    public void setForensicsFlagsJson(String forensicsFlagsJson) {
        this.forensicsFlagsJson = forensicsFlagsJson;
    }
    
    public Double getGroqRiskScore() {
        return groqRiskScore;
    }

    public void setGroqRiskScore(Double groqRiskScore) {
        this.groqRiskScore = groqRiskScore;
    }

    public String getGroqSummary() {
        return groqSummary;
    }

    public void setGroqSummary(String groqSummary) {
        this.groqSummary = groqSummary;
    }

    public String getGroqRedFlagsJson() {
        return groqRedFlagsJson;
    }

    public void setGroqRedFlagsJson(String groqRedFlagsJson) {
        this.groqRedFlagsJson = groqRedFlagsJson;
    }

    public String getGroqScamReportsJson() {
        return groqScamReportsJson;
    }

    public void setGroqScamReportsJson(String groqScamReportsJson) {
        this.groqScamReportsJson = groqScamReportsJson;
    }

    public String getEvidenceFlagsJson() {
        return evidenceFlagsJson;
    }

    public void setEvidenceFlagsJson(String evidenceFlagsJson) {
        this.evidenceFlagsJson = evidenceFlagsJson;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getImageFilename() {
        return imageFilename;
    }

    public void setImageFilename(String imageFilename) {
        this.imageFilename = imageFilename;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public String getLinkedinUrl() {
        return linkedinUrl;
    }

    public void setLinkedinUrl(String linkedinUrl) {
        this.linkedinUrl = linkedinUrl;
    }

    public String getWhatsappSenderNumber() {
        return whatsappSenderNumber;
    }

    public void setWhatsappSenderNumber(String whatsappSenderNumber) {
        this.whatsappSenderNumber = whatsappSenderNumber;
    }

    public String getWhatsappMessageId() {
        return whatsappMessageId;
    }

    public void setWhatsappMessageId(String whatsappMessageId) {
        this.whatsappMessageId = whatsappMessageId;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public void setPublicToken(String publicToken) {
        this.publicToken = publicToken;
    }
}
