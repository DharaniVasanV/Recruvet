package com.fakejobpostsystem.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fakejobpostsystem.model.OutcomeReport.ModerationStatus;
import com.fakejobpostsystem.repository.OutcomeReportRepository;
import com.fakejobpostsystem.service.OutcomeReportService;

@Controller
@RequestMapping("/admin/outcome-reports")
public class AdminOutcomeReportController {

    private final OutcomeReportRepository outcomeReportRepository;
    private final OutcomeReportService outcomeReportService;

    public AdminOutcomeReportController(
            OutcomeReportRepository outcomeReportRepository,
            OutcomeReportService outcomeReportService) {
        this.outcomeReportRepository = outcomeReportRepository;
        this.outcomeReportService = outcomeReportService;
    }

    @GetMapping
    public String pendingReports(Model model) {
        model.addAttribute("reports", outcomeReportRepository.findByModerationStatusOrderBySubmittedAtDesc(ModerationStatus.PENDING));
        return "admin-outcome-reports";
    }

    @PostMapping("/{reportId}/approve")
    public String approve(@PathVariable Long reportId, RedirectAttributes redirectAttributes) {
        outcomeReportService.moderate(reportId, ModerationStatus.APPROVED);
        redirectAttributes.addFlashAttribute("successMessage", "Outcome report approved.");
        return "redirect:/admin/outcome-reports";
    }

    @PostMapping("/{reportId}/reject")
    public String reject(@PathVariable Long reportId, RedirectAttributes redirectAttributes) {
        outcomeReportService.moderate(reportId, ModerationStatus.REJECTED);
        redirectAttributes.addFlashAttribute("successMessage", "Outcome report rejected.");
        return "redirect:/admin/outcome-reports";
    }
}
