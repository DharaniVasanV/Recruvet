package com.fakejobpostsystem.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.dto.CompanyInfo;
import com.fakejobpostsystem.dto.Entities;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.dto.RedFlagCheck.SignalCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class DomainAgeCheckService {

    private static final String WHOIS_URL = "https://www.whoisxmlapi.com/whoisserver/WhoisService";
    private static final long NEW_DOMAIN_DAYS = 90;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public DomainAgeCheckService(
            ObjectMapper objectMapper,
            @Value("${whoisxml.api-key:${WHOISXML_API_KEY:${WhoisXML_api_key:}}}") String apiKey) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public Optional<RedFlagCheck> check(CompanyInfo companyInfo, Entities entities) {
        if (apiKey.isBlank()) {
            return Optional.empty();
        }

        String domain = resolveDomain(companyInfo, entities);
        if (domain == null || domain.isBlank()) {
            return Optional.empty();
        }

        try {
            String url = WHOIS_URL
                    + "?apiKey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                    + "&domainName=" + URLEncoder.encode(domain, StandardCharsets.UTF_8)
                    + "&outputFormat=JSON";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body()).path("WhoisRecord");
            LocalDate createdDate = firstDate(
                    root.path("createdDateNormalized").asText(null),
                    root.path("registryData").path("createdDateNormalized").asText(null),
                    root.path("createdDate").asText(null),
                    root.path("registryData").path("createdDate").asText(null));
            if (createdDate == null) {
                return Optional.empty();
            }

            long ageDays = Duration.between(createdDate.atStartOfDay().toInstant(ZoneOffset.UTC), java.time.Instant.now()).toDays();
            if (ageDays <= NEW_DOMAIN_DAYS) {
                return Optional.of(new RedFlagCheck(
                        "Very new company domain",
                        domain + " was registered on " + createdDate + " (" + ageDays + " days old)",
                        "Scam job offers often use recently registered domains that have little public history.",
                        "https://whois.whoisxmlapi.com/lookup/" + URLEncoder.encode(domain, StandardCharsets.UTF_8),
                        SignalCategory.DOMAIN_AGE,
                        null));
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.empty();
    }

    private String resolveDomain(CompanyInfo companyInfo, Entities entities) {
        String websiteDomain = companyInfo == null ? null : extractDomain(companyInfo.website());
        if (isUsableCompanyDomain(websiteDomain)) {
            return websiteDomain;
        }

        List<String> emails = entities == null || entities.emails() == null ? List.of() : entities.emails();
        for (String email : emails) {
            int at = email == null ? -1 : email.lastIndexOf('@');
            if (at >= 0 && at < email.length() - 1) {
                String domain = cleanupHost(email.substring(at + 1));
                if (isUsableCompanyDomain(domain)) {
                    return domain;
                }
            }
        }
        return null;
    }

    private String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.startsWith("http") ? url : "https://" + url);
            return cleanupHost(uri.getHost());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String cleanupHost(String host) {
        if (host == null) {
            return null;
        }
        String cleaned = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private boolean isUsableCompanyDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        return !domain.contains("google.")
                && !domain.contains("linkedin.")
                && !domain.contains("facebook.")
                && !domain.contains("indeed.")
                && !domain.contains("glassdoor.");
    }

    private LocalDate firstDate(String... candidates) {
        for (String candidate : candidates) {
            LocalDate parsed = parseDate(candidate);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.trim();
        try {
            return OffsetDateTime.parse(cleaned, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z", Locale.ROOT),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
                DateTimeFormatter.ISO_LOCAL_DATE)) {
            try {
                return formatter.parse(cleaned, LocalDate::from);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }
}
