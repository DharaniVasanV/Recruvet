package com.fakejobpostsystem.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fakejobpostsystem.model.CompanyReputationAggregate;
import com.fakejobpostsystem.model.Institution;
import com.fakejobpostsystem.model.Prediction;
import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.repository.BatchJobRepository;
import com.fakejobpostsystem.repository.CompanyReputationAggregateRepository;
import com.fakejobpostsystem.repository.PredictionRepository;
import com.fakejobpostsystem.repository.UserRepository;
import com.fakejobpostsystem.service.OutcomeReportService;
import com.fakejobpostsystem.service.TpoAccessService;
import com.fakejobpostsystem.service.TpoBatchProcessingService;
import com.fakejobpostsystem.service.TpoBatchProcessingService.CsvPosting;

@Controller
public class TpoDashboardController {

    private final TpoAccessService tpoAccessService;
    private final TpoBatchProcessingService batchProcessingService;
    private final BatchJobRepository batchJobRepository;
    private final PredictionRepository predictionRepository;
    private final UserRepository userRepository;
    private final CompanyReputationAggregateRepository aggregateRepository;
    private final OutcomeReportService outcomeReportService;

    public TpoDashboardController(
            TpoAccessService tpoAccessService,
            TpoBatchProcessingService batchProcessingService,
            BatchJobRepository batchJobRepository,
            PredictionRepository predictionRepository,
            UserRepository userRepository,
            CompanyReputationAggregateRepository aggregateRepository,
            OutcomeReportService outcomeReportService) {
        this.tpoAccessService = tpoAccessService;
        this.batchProcessingService = batchProcessingService;
        this.batchJobRepository = batchJobRepository;
        this.predictionRepository = predictionRepository;
        this.userRepository = userRepository;
        this.aggregateRepository = aggregateRepository;
        this.outcomeReportService = outcomeReportService;
    }

    @GetMapping("/tpo/dashboard")
    public String dashboard(
            Authentication authentication,
            @RequestParam(name = "risk", defaultValue = "all") String risk,
            Model model) {
        Institution institution = tpoAccessService.requireInstitution(authentication);
        List<Prediction> postings = switch (risk.toLowerCase()) {
            case "high" -> predictionRepository.findByInstitution_IdAndScoreGreaterThanEqualOrderByTimestampDesc(institution.getId(), 0.7);
            case "suspicious" -> predictionRepository.findByInstitution_IdAndScoreGreaterThanEqualOrderByTimestampDesc(institution.getId(), 0.3);
            default -> predictionRepository.findByInstitution_IdOrderByTimestampDesc(institution.getId());
        };
        postings = postings.stream()
                .filter(prediction -> !isSharedStudentPrediction(prediction))
                .toList();
        List<Prediction> sharedStudentPredictions = predictionRepository.findVisibleStudentSharedPredictions(institution.getId())
                .stream()
                .filter(prediction -> matchesRiskFilter(prediction, risk))
                .toList();

        Map<String, CompanyReputationAggregate> repeatOffenders = aggregateRepository
                .findByScamReportCountGreaterThanEqualAndManualReviewRequiredFalse(3)
                .stream()
                .collect(Collectors.toMap(
                        CompanyReputationAggregate::getCompanyIdentifier,
                        aggregate -> aggregate,
                        (left, right) -> left));
        Map<Long, CompanyReputationAggregate> repeatOffendersByPrediction = java.util.stream.Stream
                .concat(postings.stream(), sharedStudentPredictions.stream())
                .filter(prediction -> repeatOffenders.containsKey(outcomeReportService.normalizeCompanyIdentifier(prediction.getCompanyName())))
                .collect(Collectors.toMap(
                        Prediction::getId,
                        prediction -> repeatOffenders.get(outcomeReportService.normalizeCompanyIdentifier(prediction.getCompanyName())),
                        (left, right) -> left));

        model.addAttribute("institution", institution);
        model.addAttribute("postings", postings);
        model.addAttribute("sharedStudentPredictions", sharedStudentPredictions);
        model.addAttribute("sharingStudentCount", userRepository.countByInstitution_IdAndRoleAndShareWithInstitutionTrue(institution.getId(), "ROLE_USER"));
        model.addAttribute("sharedSubmissionCount", sharedStudentPredictions.size());
        model.addAttribute("batchJobs", batchJobRepository.findTop10ByInstitution_IdOrderBySubmittedAtDesc(institution.getId()));
        model.addAttribute("repeatOffendersByPrediction", repeatOffendersByPrediction);
        model.addAttribute("selectedRisk", risk);
        return "tpo-dashboard";
    }

    private boolean matchesRiskFilter(Prediction prediction, String risk) {
        double score = prediction.getScore() == null ? 0.0 : prediction.getScore();
        return switch (risk == null ? "all" : risk.toLowerCase()) {
            case "high" -> score >= 0.7;
            case "suspicious" -> score >= 0.3;
            default -> true;
        };
    }

    private boolean isSharedStudentPrediction(Prediction prediction) {
        User owner = prediction.getUser();
        return owner != null && "ROLE_USER".equals(owner.getRole()) && prediction.isSharedWithInstitution();
    }

    @PostMapping("/tpo/batches")
    public String uploadBatch(
            Authentication authentication,
            @RequestParam("csv_file") MultipartFile csvFile,
            RedirectAttributes redirectAttributes) {
        try {
            User user = tpoAccessService.requireTpoUser(authentication);
            if (csvFile == null || csvFile.isEmpty()) {
                redirectAttributes.addFlashAttribute("errorMessage", "Please upload a CSV file.");
                return "redirect:/tpo/dashboard";
            }

            List<CsvPosting> rows = batchProcessingService.parseCsv(new String(csvFile.getBytes()));
            var job = batchProcessingService.createJob(user.getInstitution(), rows);
            batchProcessingService.processAsync(job.getId(), user.getId(), rows);
            redirectAttributes.addFlashAttribute("successMessage", "Batch queued. Results will appear as rows finish.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Batch upload failed: " + ex.getMessage());
        }
        return "redirect:/tpo/dashboard";
    }
}
