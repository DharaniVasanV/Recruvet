package com.fakejobpostsystem.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.fakejobpostsystem.model.OutcomeReport.ConfirmedStatus;
import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.service.CurrentUserService;
import com.fakejobpostsystem.service.OutcomeReportService;

@Controller
public class OutcomeReportController {

    private final OutcomeReportService outcomeReportService;
    private final CurrentUserService currentUserService;

    public OutcomeReportController(OutcomeReportService outcomeReportService, CurrentUserService currentUserService) {
        this.outcomeReportService = outcomeReportService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/reports")
    public String submitReport(
            Authentication authentication,
            @RequestParam("companyName") String companyName,
            @RequestParam(name = "companyEmailDomain", required = false) String companyEmailDomain,
            @RequestParam(name = "phoneNumber", required = false) String phoneNumber,
            @RequestParam("confirmedStatus") ConfirmedStatus confirmedStatus,
            @RequestParam(name = "evidenceText", required = false) String evidenceText,
            RedirectAttributes redirectAttributes) {
        User user = currentUserService.requireUser(authentication);
        outcomeReportService.submitReport(user, companyName, companyEmailDomain, phoneNumber, confirmedStatus, evidenceText);
        redirectAttributes.addFlashAttribute("successMessage", "Outcome report submitted for admin review.");
        return "redirect:/dashboard";
    }
}
