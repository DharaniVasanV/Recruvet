package com.fakejobpostsystem.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.model.Institution;
import com.fakejobpostsystem.model.Prediction;
import com.fakejobpostsystem.repository.InstitutionRepository;
import com.fakejobpostsystem.repository.PredictionRepository;
import com.fakejobpostsystem.repository.UserRepository;

@Service
public class TpoWeeklyDigestService {

    private final JavaMailSender mailSender;
    private final InstitutionRepository institutionRepository;
    private final PredictionRepository predictionRepository;
    private final UserRepository userRepository;
    private final String fromAddress;
    private final boolean digestEnabled;

    public TpoWeeklyDigestService(
            JavaMailSender mailSender,
            InstitutionRepository institutionRepository,
            PredictionRepository predictionRepository,
            UserRepository userRepository,
            @Value("${tpo.digest.from:${spring.mail.username:}}") String fromAddress,
            @Value("${tpo.digest.enabled:false}") boolean digestEnabled) {
        this.mailSender = mailSender;
        this.institutionRepository = institutionRepository;
        this.predictionRepository = predictionRepository;
        this.userRepository = userRepository;
        this.fromAddress = fromAddress;
        this.digestEnabled = digestEnabled;
    }

    @Scheduled(cron = "${tpo.digest.cron:0 0 9 ? * MON}")
    public void sendWeeklyDigest() {
        if (!digestEnabled || fromAddress == null || fromAddress.isBlank()) {
            return;
        }

        LocalDateTime since = LocalDateTime.now().minusDays(7);
        for (Institution institution : institutionRepository.findAll()) {
            List<Prediction> highRisk = predictionRepository
                    .findByInstitution_IdAndScoreGreaterThanEqualOrderByTimestampDesc(institution.getId(), 0.7)
                    .stream()
                    .filter(prediction -> prediction.getTimestamp() != null && prediction.getTimestamp().isAfter(since))
                    .toList();
            if (highRisk.isEmpty()) {
                continue;
            }

            String body = buildBody(institution, highRisk);
            userRepository.findByRoleAndInstitution_Id("ROLE_TPO", institution.getId())
                    .forEach(user -> send(user.getEmail(), "Weekly high-risk placement posting digest", body));
        }
    }

    private void send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception ex) {
            System.err.println("Could not send TPO weekly digest to " + to + ": " + ex.getMessage());
        }
    }

    private String buildBody(Institution institution, List<Prediction> highRisk) {
        StringBuilder body = new StringBuilder();
        body.append("Weekly placement fraud digest for ").append(institution.getName()).append("\n\n");
        body.append("New high-risk postings this week: ").append(highRisk.size()).append("\n\n");
        for (Prediction prediction : highRisk) {
            body.append("- ")
                    .append(prediction.getCompanyName() == null ? "Unknown company" : prediction.getCompanyName())
                    .append(" | Risk: ")
                    .append(String.format(java.util.Locale.ROOT, "%.1f%%", prediction.getScore() * 100.0))
                    .append(" | Date: ")
                    .append(prediction.getTimestamp())
                    .append("\n");
        }
        body.append("\nOpen the TPO dashboard for full evidence and batch status.");
        return body.toString();
    }
}
