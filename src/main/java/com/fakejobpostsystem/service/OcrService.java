package com.fakejobpostsystem.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OcrService {

    private final String tesseractCommand;

    public OcrService(@Value("${app.tesseract-command:tesseract}") String tesseractCommand) {
        this.tesseractCommand = tesseractCommand;
    }

    public String extractText(Path imagePath) {
        try {
            Process process = new ProcessBuilder(tesseractCommand, imagePath.toString(), "stdout")
                    .redirectErrorStream(true)
                    .start();
            String output = FileStorageService.readAll(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return output == null ? "" : output.trim();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
        }

        return "";
    }

    public String extractText(BufferedImage image) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("ocr-page-", ".png");
            ImageIO.write(image, "png", tempFile.toFile());
            return extractText(tempFile);
        } catch (IOException ex) {
            return "";
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
