package com.fakejobpostsystem.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import com.fakejobpostsystem.dto.Entities;
import com.fakejobpostsystem.dto.ForensicsResult;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.service.PdfTextExtractionService.PdfDocumentAnalysis;
import com.fakejobpostsystem.service.PdfTextExtractionService.PdfPageAnalysis;
import com.fakejobpostsystem.service.PdfTextExtractionService.PdfTextSpan;

@Service
public class DocumentForensicsService {

    private static final double SIGNATURE_INK_THRESHOLD = 0.006;

    private final PdfTextExtractionService pdfTextExtractionService;
    private final EntityExtractionService entityExtractionService;

    public DocumentForensicsService(
            PdfTextExtractionService pdfTextExtractionService,
            EntityExtractionService entityExtractionService) {
        this.pdfTextExtractionService = pdfTextExtractionService;
        this.entityExtractionService = entityExtractionService;
    }

    public ForensicsResult analyze(String text, Path sourcePath, String sourceType, Entities extractedEntities) {
        String safeText = text == null ? "" : text;
        List<RedFlagCheck> flags = new ArrayList<>();

        PdfDocumentAnalysis pdfAnalysis = isPdf(sourceType, sourcePath)
                ? pdfTextExtractionService.extractAnalysis(sourcePath)
                : PdfDocumentAnalysis.empty();

        DocumentZones zones = pdfAnalysis.pages().isEmpty()
                ? zonesFromPlainText(safeText)
                : zonesFromPdf(pdfAnalysis);

        detectCompanyMismatch(zones, flags);
        detectMissingStandardFields(safeText, extractedEntities, flags);
        detectFontInconsistencies(pdfAnalysis, flags);
        detectMissingSignatureOrStamp(sourcePath, sourceType, pdfAnalysis, flags);

        return new ForensicsResult(score(flags), List.copyOf(flags));
    }

    private void detectCompanyMismatch(DocumentZones zones, List<RedFlagCheck> flags) {
        Map<String, String> zoneCompanies = new LinkedHashMap<>();
        putCompany(zoneCompanies, "Header", zones.header());
        putCompany(zoneCompanies, "Body", zones.body());
        putCompany(zoneCompanies, "Signature block", zones.signature());

        Map<String, String> normalizedToDisplay = new LinkedHashMap<>();
        zoneCompanies.forEach((zone, company) -> {
            String normalized = normalizeCompany(company);
            if (!normalized.isBlank()) {
                normalizedToDisplay.putIfAbsent(normalized, zone + ": " + company);
            }
        });

        if (normalizedToDisplay.size() > 1) {
            flags.add(new RedFlagCheck(
                    "Company name mismatch",
                    String.join("; ", normalizedToDisplay.values()),
                    "The company name appears differently in the header, body, or signature block, which can indicate document tampering."));
        }
    }

    private void putCompany(Map<String, String> zoneCompanies, String zone, String zoneText) {
        if (zoneText == null || zoneText.isBlank()) {
            return;
        }
        Entities zoneEntities = entityExtractionService.extract(zoneText);
        String company = entityExtractionService.inferCompanyNameFromText(zoneText, zoneEntities);
        company = entityExtractionService.normalizeCompanyDisplayName(company);
        if (company != null && !company.isBlank() && !isGenericCompany(company)) {
            zoneCompanies.put(zone, company);
        }
    }

    private void detectMissingStandardFields(String text, Entities entities, List<RedFlagCheck> flags) {
        String normalized = normalize(text);
        addMissingFlagIfFalse(flags, hasRegisteredAddress(normalized), "Registered address missing",
                "No registered office, corporate office, or clear address field was detected.",
                "Genuine offer letters usually include a registered/corporate address or verifiable office address.");
        addMissingFlagIfFalse(flags, hasHrContact(normalized, entities), "HR contact details incomplete",
                "No clear HR contact name with designation was detected.",
                "A formal offer letter normally identifies an HR/recruiter/contact person with role or designation.");
        addMissingFlagIfFalse(flags, hasJoiningDate(normalized), "Joining date missing",
                "No joining date, start date, reporting date, or commencement date was detected.",
                "Missing joining timelines reduce the document's professional completeness.");
        addMissingFlagIfFalse(flags, hasCtcBreakup(normalized), "CTC or compensation details missing",
                "No CTC, salary, stipend, compensation, or breakup details were detected.",
                "Offer letters generally state compensation or clearly say if the role is unpaid.");
        addMissingFlagIfFalse(flags, hasEmploymentTerms(normalized), "Employment terms missing",
                "No probation, notice period, duration, work hours, or employment terms were detected.",
                "Formal letters usually specify basic employment conditions.");
        addMissingFlagIfFalse(flags, hasAuthorizedSignatory(normalized), "Authorized signatory missing",
                "No authorized signatory, signed-by section, or closing signature context was detected.",
                "A missing signatory section can be a forgery or template misuse signal.");
    }

    private void detectFontInconsistencies(PdfDocumentAnalysis analysis, List<RedFlagCheck> flags) {
        List<PdfTextSpan> bodySpans = analysis.pages().stream()
                .flatMap(page -> page.spans().stream()
                        .filter(span -> isBodySpan(page, span)))
                .filter(span -> span.text().length() >= 12)
                .toList();
        if (bodySpans.size() < 4) {
            return;
        }

        String dominantStyle = dominantStyle(bodySpans);
        int added = 0;
        for (PdfTextSpan span : bodySpans) {
            if (added >= 3) {
                break;
            }
            String normalized = normalize(span.text());
            if (!containsAny(normalized, "salary", "ctc", "compensation", "designation", "position", "joining date", "date of joining", "authorized signatory", "authorised signatory")) {
                continue;
            }
            String style = styleKey(span);
            if (!Objects.equals(style, dominantStyle)) {
                flags.add(new RedFlagCheck(
                        "Font inconsistency near key term",
                        span.text() + " [" + readableStyle(span) + "]",
                        "A salary, designation, joining, or signatory-related line uses a different font/size than the surrounding body text."));
                added++;
            }
        }
    }

    private void detectMissingSignatureOrStamp(
            Path sourcePath,
            String sourceType,
            PdfDocumentAnalysis pdfAnalysis,
            List<RedFlagCheck> flags) {
        if (sourcePath == null || "text".equalsIgnoreCase(sourceType)) {
            return;
        }

        double density = 0.0;
        if (isPdf(sourceType, sourcePath)) {
            density = pdfAnalysis.bottomInkDensity();
        } else {
            try {
                BufferedImage image = ImageIO.read(sourcePath.toFile());
                density = pdfTextExtractionService.measureBottomInkDensity(image);
            } catch (IOException ignored) {
                density = 0.0;
            }
        }

        // V1 heuristic: bottom-third ink density is a coarse signature/stamp signal.
        // It can be upgraded later with actual signature/stamp detection models.
        if (density < SIGNATURE_INK_THRESHOLD) {
            flags.add(new RedFlagCheck(
                    "Signature or stamp not visible",
                    "Bottom-third ink density: " + String.format(Locale.ROOT, "%.3f", density),
                    "The lower portion of the document appears visually sparse, so no obvious signature/stamp region was detected."));
        }
    }

    private DocumentZones zonesFromPdf(PdfDocumentAnalysis analysis) {
        StringBuilder header = new StringBuilder();
        StringBuilder body = new StringBuilder();
        StringBuilder signature = new StringBuilder();
        int lastPage = analysis.pages().isEmpty() ? 0 : analysis.pages().size() - 1;

        for (PdfPageAnalysis page : analysis.pages()) {
            for (PdfTextSpan span : page.spans()) {
                double yRatio = page.height() <= 0 ? 0.5 : span.y() / page.height();
                if (page.pageIndex() == 0 && yRatio <= 0.25) {
                    appendLine(header, span.text());
                } else if (page.pageIndex() == lastPage && yRatio >= 0.66) {
                    appendLine(signature, span.text());
                } else {
                    appendLine(body, span.text());
                }
            }
        }

        return new DocumentZones(header.toString(), body.toString(), signature.toString());
    }

    private DocumentZones zonesFromPlainText(String text) {
        String[] lines = text == null ? new String[0] : text.split("\\R+");
        StringBuilder header = new StringBuilder();
        StringBuilder body = new StringBuilder();
        StringBuilder signature = new StringBuilder();
        int headerEnd = Math.max(1, (int) Math.ceil(lines.length * 0.20));
        int signatureStart = Math.max(headerEnd, (int) Math.floor(lines.length * 0.75));

        for (int i = 0; i < lines.length; i++) {
            if (i < headerEnd) {
                appendLine(header, lines[i]);
            } else if (i >= signatureStart) {
                appendLine(signature, lines[i]);
            } else {
                appendLine(body, lines[i]);
            }
        }
        return new DocumentZones(header.toString(), body.toString(), signature.toString());
    }

    private boolean hasRegisteredAddress(String normalized) {
        return containsAny(normalized, "registered address", "registered office", "corporate office", "head office")
                || (containsAny(normalized, "address", "street", " st ", "road", "city") && containsAny(normalized, "pin", "pincode", "zip", "state"));
    }

    private boolean hasHrContact(String normalized, Entities entities) {
        boolean hasRole = containsAny(normalized, "hr", "human resources", "recruiter", "hiring manager", "talent acquisition", "manager");
        boolean hasPerson = entities != null && entities.persons() != null && !entities.persons().isEmpty();
        boolean hasEmail = entities != null && entities.emails() != null && !entities.emails().isEmpty();
        return hasRole && (hasPerson || hasEmail);
    }

    private boolean hasJoiningDate(String normalized) {
        return containsAny(normalized, "joining date", "date of joining", "start date", "commencement date", "reporting date", "join on", "joining");
    }

    private boolean hasCtcBreakup(String normalized) {
        return containsAny(normalized, "ctc", "cost to company", "salary", "stipend", "compensation", "breakup", "allowance", "bonus", "unpaid");
    }

    private boolean hasEmploymentTerms(String normalized) {
        return containsAny(normalized, "employment terms", "terms and conditions", "probation", "notice period", "duration", "work hours", "working hours", "confidentiality");
    }

    private boolean hasAuthorizedSignatory(String normalized) {
        return containsAny(normalized, "authorized signatory", "authorised signatory", "signed by", "signature", "best regards", "sincerely", "for and on behalf");
    }

    private void addMissingFlagIfFalse(List<RedFlagCheck> flags, boolean present, String flag, String evidence, String explanation) {
        if (!present) {
            flags.add(new RedFlagCheck(flag, evidence, explanation));
        }
    }

    private boolean isPdf(String sourceType, Path sourcePath) {
        return "pdf".equalsIgnoreCase(sourceType)
                || (sourcePath != null && sourcePath.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private boolean isBodySpan(PdfPageAnalysis page, PdfTextSpan span) {
        double yRatio = page.height() <= 0 ? 0.5 : span.y() / page.height();
        return yRatio > 0.25 && yRatio < 0.66;
    }

    private String dominantStyle(List<PdfTextSpan> spans) {
        return spans.stream()
                .collect(java.util.stream.Collectors.groupingBy(this::styleKey, LinkedHashMap::new, java.util.stream.Collectors.counting()))
                .entrySet()
                .stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private String styleKey(PdfTextSpan span) {
        return baseFontName(span.fontName()) + ":" + Math.round(span.fontSize());
    }

    private String readableStyle(PdfTextSpan span) {
        return baseFontName(span.fontName()) + ", " + String.format(Locale.ROOT, "%.1fpt", span.fontSize());
    }

    private String baseFontName(String fontName) {
        if (fontName == null || fontName.isBlank()) {
            return "unknown";
        }
        return fontName.replaceFirst("^[A-Z]{6}\\+", "")
                .replaceAll("(?i)[,_-]?(bold|italic|regular|medium|light|roman|semi).*", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private double score(List<RedFlagCheck> flags) {
        double score = 0.0;
        for (RedFlagCheck flag : flags) {
            String normalized = normalize(flag.flag());
            if (normalized.contains("company name mismatch")) {
                score += 0.25;
            } else if (normalized.contains("font inconsistency")) {
                score += 0.18;
            } else if (normalized.contains("signature") || normalized.contains("stamp")) {
                score += 0.15;
            } else {
                score += 0.07;
            }
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private boolean isGenericCompany(String company) {
        String normalized = normalizeCompany(company);
        return normalized.equals("company")
                || normalized.equals("candidate")
                || normalized.equals("offer letter")
                || normalized.equals("internship");
    }

    private String normalizeCompany(String company) {
        if (company == null) {
            return "";
        }
        return entityExtractionService.normalizeCompanyDisplayName(company)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private String normalize(String value) {
        return value == null ? "" : (" " + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private void appendLine(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text.trim());
    }

    private record DocumentZones(String header, String body, String signature) {
    }
}
