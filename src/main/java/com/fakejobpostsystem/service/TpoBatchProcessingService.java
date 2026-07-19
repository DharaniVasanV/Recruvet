package com.fakejobpostsystem.service;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fakejobpostsystem.dto.ReviewVerificationResult;
import com.fakejobpostsystem.model.BatchJob;
import com.fakejobpostsystem.model.BatchJob.BatchStatus;
import com.fakejobpostsystem.model.Institution;
import com.fakejobpostsystem.model.Prediction;
import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.repository.BatchJobRepository;
import com.fakejobpostsystem.repository.PredictionRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class TpoBatchProcessingService {

    private final BatchJobRepository batchJobRepository;
    private final PredictionRepository predictionRepository;
    private final DetectionService detectionService;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public TpoBatchProcessingService(
            BatchJobRepository batchJobRepository,
            PredictionRepository predictionRepository,
            DetectionService detectionService,
            ObjectMapper objectMapper) {
        this.batchJobRepository = batchJobRepository;
        this.predictionRepository = predictionRepository;
        this.detectionService = detectionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BatchJob createJob(Institution institution, List<CsvPosting> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("CSV must contain at least one posting row.");
        }

        BatchJob job = new BatchJob();
        job.setInstitution(institution);
        job.setStatus(BatchStatus.QUEUED);
        job.setTotalRows(rows.size());
        job.setProcessedRows(0);
        return batchJobRepository.save(job);
    }

    @Async
    @Transactional
    public void processAsync(Long batchJobId, Long submitterUserId, List<CsvPosting> rows) {
        BatchJob job = batchJobRepository.findById(batchJobId).orElse(null);
        if (job == null) {
            return;
        }

        try {
            job.setStatus(BatchStatus.RUNNING);
            batchJobRepository.save(job);

            for (CsvPosting row : rows) {
                processRow(job, submitterUserId, row);
                job.setProcessedRows((job.getProcessedRows() == null ? 0 : job.getProcessedRows()) + 1);
                batchJobRepository.save(job);
            }

            job.setStatus(BatchStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            batchJobRepository.save(job);
        } catch (Exception ex) {
            job.setStatus(BatchStatus.FAILED);
            job.setErrorMessage(ex.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            batchJobRepository.save(job);
        }
    }

    public List<CsvPosting> parseCsv(String csvContent) throws Exception {
        List<CsvPosting> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent == null ? "" : csvContent))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> columns = parseCsvLine(line);
                if (firstLine && looksLikeHeader(columns)) {
                    firstLine = false;
                    continue;
                }
                firstLine = false;

                String companyName = columns.isEmpty() ? "" : columns.get(0).trim();
                String postingText = columns.size() > 1 ? columns.get(1).trim() : "";
                if (!companyName.isBlank() && !postingText.isBlank()) {
                    rows.add(new CsvPosting(companyName, postingText));
                }
            }
        }
        return rows;
    }

    @Transactional
    protected void processRow(BatchJob job, Long submitterUserId, CsvPosting row) throws Exception {
        String inputText = row.companyName() + "\n\n" + resolvePostingText(row.postingText());
        var result = detectionService.analyze(inputText, null);
        ReviewVerificationResult reviewResult = result.reviewVerification();

        Prediction prediction = new Prediction();
        prediction.setInstitution(job.getInstitution());
        prediction.setBatchJob(job);
        prediction.setUser(entityManager.getReference(User.class, submitterUserId));
        prediction.setJobText(detectionService.preview(result.text()));
        prediction.setScore(result.score());
        prediction.setMlScore(result.mlScore());
        prediction.setReviewRiskScore(result.reviewRiskScore());
        prediction.setSourceType("tpo_csv");
        prediction.setCompanyName(result.companyName() == null || result.companyName().isBlank() ? row.companyName() : result.companyName());
        prediction.setWebsiteUrl(result.companyInfo() == null ? null : result.companyInfo().website());
        prediction.setLinkedinUrl(result.companyInfo() == null ? null : result.companyInfo().linkedin());

        if (reviewResult != null) {
            prediction.setReviewSummary(reviewResult.summary());
            prediction.setReviewPositiveCount(reviewResult.positiveCount());
            prediction.setReviewNegativeCount(reviewResult.negativeCount());
            prediction.setReviewSourcesJson(objectMapper.writeValueAsString(reviewResult.sources()));
            prediction.setReviewEvidenceTypesJson(objectMapper.writeValueAsString(reviewResult.evidenceTypes()));
        }
        if (result.groqVerification() != null) {
            prediction.setGroqRiskScore(result.groqVerification().riskScore());
            prediction.setGroqSummary(result.groqVerification().summary());
            prediction.setGroqRedFlagsJson(objectMapper.writeValueAsString(result.groqVerification().redFlags()));
            prediction.setGroqScamReportsJson(objectMapper.writeValueAsString(result.groqVerification().scamReports()));
        }
        prediction.setEvidenceFlagsJson(objectMapper.writeValueAsString(result.evidenceTrail()));
        predictionRepository.save(prediction);
    }

    private boolean looksLikeHeader(List<String> columns) {
        if (columns.isEmpty()) {
            return false;
        }
        String first = columns.get(0).toLowerCase();
        return first.contains("company") || first.contains("organisation") || first.contains("organization");
    }

    private String resolvePostingText(String postingTextOrUrl) {
        String value = postingTextOrUrl == null ? "" : postingTextOrUrl.trim();
        if (!value.matches("(?i)^https?://.+")) {
            return value;
        }
        try {
            return Jsoup.connect(value)
                    .timeout(12000)
                    .userAgent("Mozilla/5.0 FakeJobPostSystem TPO Screening")
                    .get()
                    .text();
        } catch (Exception ex) {
            return value + "\n\nCould not fetch URL content: " + ex.getMessage();
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    public record CsvPosting(String companyName, String postingText) {
    }
}
