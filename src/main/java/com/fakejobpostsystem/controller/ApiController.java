package com.fakejobpostsystem.controller;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fakejobpostsystem.dto.PredictionPoint;
import com.fakejobpostsystem.model.User;
import com.fakejobpostsystem.repository.PredictionRepository;
import com.fakejobpostsystem.service.CurrentUserService;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PredictionRepository predictionRepository;
    private final CurrentUserService currentUserService;

    public ApiController(PredictionRepository predictionRepository, CurrentUserService currentUserService) {
        this.predictionRepository = predictionRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/predictions")
    public List<PredictionPoint> getPredictions(Authentication authentication) {
        User user = currentUserService.requireUser(authentication);
        return predictionRepository.findByUser_IdOrderByTimestampDesc(user.getId())
                .stream()
                .map(prediction -> new PredictionPoint(
                        prediction.getTimestamp() == null ? "N/A" : prediction.getTimestamp().format(TIMESTAMP_FORMATTER),
                        prediction.getScore(),
                        prediction.getJobText().length() > 50 ? prediction.getJobText().substring(0, 50) + "..." : prediction.getJobText()))
                .toList();
    }
}
