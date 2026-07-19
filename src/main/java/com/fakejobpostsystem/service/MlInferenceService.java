package com.fakejobpostsystem.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MlInferenceService {

    private final String pythonCommand;

    public MlInferenceService(@Value("${app.python-command:python}") String pythonCommand) {
        this.pythonCommand = pythonCommand;
    }

    public double scoreText(String text) {
        Path script = Path.of("scripts", "predict_score.py").toAbsolutePath();
        if (!script.toFile().exists()) {
            return fallbackScore(text);
        }

        try {
            Process process = new ProcessBuilder(pythonCommand, script.toString())
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String output = FileStorageService.readAll(process.getInputStream()).trim();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Double parsed = parseScore(output);
                if (parsed != null) {
                    return clamp(parsed);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
        }

        return fallbackScore(text);
    }

    private Double parseScore(String output) {
        try {
            return Double.parseDouble(output);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private double fallbackScore(String text) {
        String safeText = text == null ? "" : text.toLowerCase();
        double score = 0.08;

        if (safeText.contains("salary up to") || safeText.contains("earn from home") || safeText.contains("earn per day")) {
            score += 0.18;
        }
        if (safeText.contains("whatsapp") || safeText.contains("telegram")) {
            score += 0.16;
        }
        if (safeText.contains("no interview") || safeText.contains("no experience required")) {
            score += 0.17;
        }
        if (safeText.contains("pay") || safeText.contains("fee") || safeText.contains("deposit")) {
            score += 0.24;
        }
        if (safeText.contains("urgent") || safeText.contains("apply now") || safeText.contains("limited slots")) {
            score += 0.12;
        }
        if (safeText.contains("unpaid internship")) {
            score += 0.08;
        }
        if (safeText.length() < 80) {
            score += 0.08;
        } else if (safeText.length() > 600) {
            score -= 0.04;
        }

        return clamp(score);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
