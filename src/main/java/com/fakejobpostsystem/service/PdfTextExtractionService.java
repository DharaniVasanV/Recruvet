package com.fakejobpostsystem.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

@Service
public class PdfTextExtractionService {

    private final OcrService ocrService;

    public PdfTextExtractionService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public String extractText(Path pdfPath) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            String trimmed = text == null ? "" : text.trim();
            if (!trimmed.isBlank()) {
                return trimmed;
            }

            PDFRenderer renderer = new PDFRenderer(document);
            List<String> pageTexts = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                BufferedImage pageImage = renderer.renderImageWithDPI(i, 200, ImageType.RGB);
                String pageText = ocrService.extractText(pageImage).trim();
                if (!pageText.isBlank()) {
                    pageTexts.add(pageText);
                }
            }

            return String.join("\n\n", pageTexts).trim();
        } catch (IOException ex) {
            return "";
        }
    }

    public PdfDocumentAnalysis extractAnalysis(Path pdfPath) {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            MetadataTextStripper stripper = new MetadataTextStripper(document);
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            double bottomInkDensity = measureBottomInkDensity(document);
            return new PdfDocumentAnalysis(
                    text == null ? "" : text.trim(),
                    stripper.pageAnalyses(),
                    bottomInkDensity);
        } catch (IOException ex) {
            return PdfDocumentAnalysis.empty();
        }
    }

    private double measureBottomInkDensity(PDDocument document) {
        if (document.getNumberOfPages() == 0) {
            return 0.0;
        }
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(document.getNumberOfPages() - 1, 120, ImageType.RGB);
            return measureBottomInkDensity(image);
        } catch (IOException ex) {
            return 0.0;
        }
    }

    public double measureBottomInkDensity(BufferedImage image) {
        if (image == null) {
            return 0.0;
        }
        int startY = (int) (image.getHeight() * 0.66);
        long nonWhite = 0;
        long total = 0;
        for (int y = startY; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red < 245 || green < 245 || blue < 245) {
                    nonWhite++;
                }
                total++;
            }
        }
        return total == 0 ? 0.0 : (double) nonWhite / total;
    }

    private static class MetadataTextStripper extends PDFTextStripper {

        private final List<PdfPageAnalysis> pages;

        MetadataTextStripper(PDDocument document) throws IOException {
            this.pages = new ArrayList<>();
            int pageIndex = 0;
            for (PDPage page : document.getPages()) {
                float width = page.getMediaBox().getWidth();
                float height = page.getMediaBox().getHeight();
                pages.add(new PdfPageAnalysis(pageIndex++, width, height, new ArrayList<>()));
            }
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            super.writeString(text, textPositions);
            String cleaned = text == null ? "" : text.replaceAll("\\s+", " ").trim();
            if (cleaned.isBlank() || textPositions == null || textPositions.isEmpty()) {
                return;
            }

            int pageIndex = Math.max(0, Math.min(getCurrentPageNo() - 1, pages.size() - 1));
            TextPosition first = textPositions.get(0);
            float x = first.getXDirAdj();
            float y = first.getYDirAdj();
            float width = 0.0f;
            float height = 0.0f;
            float size = 0.0f;
            Map<String, Long> fontCounts = new LinkedHashMap<>();

            for (TextPosition position : textPositions) {
                width += position.getWidthDirAdj();
                height = Math.max(height, position.getHeightDir());
                size += position.getFontSizeInPt();
                String fontName = position.getFont() == null ? "unknown" : position.getFont().getName();
                fontCounts.put(fontName, fontCounts.getOrDefault(fontName, 0L) + 1);
            }

            String fontName = fontCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("unknown");
            float averageSize = size / textPositions.size();
            pages.get(pageIndex).spans().add(new PdfTextSpan(
                    cleaned,
                    pageIndex,
                    x,
                    y,
                    width,
                    height,
                    averageSize,
                    fontName));
        }

        List<PdfPageAnalysis> pageAnalyses() {
            return pages.stream()
                    .map(page -> new PdfPageAnalysis(
                            page.pageIndex(),
                            page.width(),
                            page.height(),
                            page.spans().stream()
                                    .sorted(Comparator.comparing(PdfTextSpan::y).thenComparing(PdfTextSpan::x))
                                    .collect(Collectors.toCollection(ArrayList::new))))
                    .toList();
        }
    }

    public record PdfDocumentAnalysis(
            String text,
            List<PdfPageAnalysis> pages,
            double bottomInkDensity
    ) {
        public static PdfDocumentAnalysis empty() {
            return new PdfDocumentAnalysis("", List.of(), 0.0);
        }
    }

    public record PdfPageAnalysis(
            int pageIndex,
            float width,
            float height,
            List<PdfTextSpan> spans
    ) {
    }

    public record PdfTextSpan(
            String text,
            int pageIndex,
            float x,
            float y,
            float width,
            float height,
            float fontSize,
            String fontName
    ) {
    }
}
