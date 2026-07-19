package com.fakejobpostsystem.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fakejobpostsystem.dto.CompanyInfo;
import com.fakejobpostsystem.dto.RedFlagCheck;
import com.fakejobpostsystem.dto.RedFlagCheck.SignalCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CompanyLookupService {

    private static final String SERPER_SEARCH_URL = "https://google.serper.dev/search";
    private static final Pattern EMPLOYEE_RANGE_PATTERN = Pattern.compile("(\\d[\\d,]*)\\s*(?:-|to)\\s*(\\d[\\d,]*)\\s+employees?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPLOYEE_COUNT_PATTERN = Pattern.compile("(\\d[\\d,]*)\\+?\\s+employees?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAIMED_EMPLOYEE_PATTERN = Pattern.compile("(?:team of|over|more than|about|around|approximately|approx\\.?|nearly)?\\s*(\\d[\\d,]*)\\+?\\s+(?:employees|staff|team members|professionals)", Pattern.CASE_INSENSITIVE);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String serperApiKey;

    public CompanyLookupService(
            ObjectMapper objectMapper,
            @Value("${SERPER_API_KEY:${Serper_api_key:}}") String serperApiKey) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
        this.serperApiKey = serperApiKey == null ? "" : serperApiKey.trim();
    }

    public CompanyInfo lookup(String companyName, String jobText) {
        if (companyName == null || companyName.isBlank() || companyName.trim().length() < 3) {
            return new CompanyInfo(companyName, null, null);
        }

        String website = null;
        String linkedin = null;

        if (!serperApiKey.isBlank()) {
            website = searchFirstOrganicLink("\"" + companyName + "\" official website", companyName, false);
            linkedin = searchFirstOrganicLink("\"" + companyName + "\" LinkedIn Page", companyName, true);
        }

        // If Serper returns no confident company match, fall back to search pages instead of a direct wrong link.
        if (website == null) {
            website = "https://www.google.com/search?q="
                    + URLEncoder.encode("\"" + companyName + "\" official website", StandardCharsets.UTF_8);
        }

        if (linkedin == null) {
            linkedin = "https://www.linkedin.com/search/results/companies/?keywords="
                    + URLEncoder.encode(companyName, StandardCharsets.UTF_8);
        }

        return new CompanyInfo(companyName, website, linkedin);
    }

    public Optional<RedFlagCheck> employeeMismatchFlag(String companyName, String jobText) {
        if (serperApiKey.isBlank() || companyName == null || companyName.isBlank() || jobText == null || jobText.isBlank()) {
            return Optional.empty();
        }

        Integer claimedCount = extractClaimedEmployeeCount(jobText).orElse(null);
        if (claimedCount == null) {
            return Optional.empty();
        }

        EmployeeCountEvidence linkedinEvidence = findLinkedinEmployeeCount(companyName).orElse(null);
        if (linkedinEvidence == null || linkedinEvidence.employeeCount() == null) {
            return Optional.empty();
        }

        int linkedinCount = linkedinEvidence.employeeCount();
        int larger = Math.max(claimedCount, linkedinCount);
        int smaller = Math.max(1, Math.min(claimedCount, linkedinCount));
        if (larger >= 100 && larger >= smaller * 3) {
            return Optional.of(new RedFlagCheck(
                    "Employee count mismatch",
                    "Post claims about " + claimedCount + " employees, but LinkedIn evidence suggests about " + linkedinCount + ".",
                    "A large mismatch between claimed company size and public LinkedIn evidence can indicate impersonation or inflated credibility.",
                    linkedinEvidence.url(),
                    SignalCategory.EMPLOYEE_MISMATCH,
                    null));
        }
        return Optional.empty();
    }

    private Optional<EmployeeCountEvidence> findLinkedinEmployeeCount(String companyName) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "q", "\"" + companyName + "\" LinkedIn company employees",
                    "gl", "in"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERPER_SEARCH_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-API-KEY", serperApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode organic = objectMapper.readTree(response.body()).path("organic");
            if (!organic.isArray()) {
                return Optional.empty();
            }

            for (JsonNode result : organic) {
                String link = result.path("link").asText("");
                if (!link.toLowerCase(Locale.ROOT).contains("linkedin.com/company/")) {
                    continue;
                }
                String title = result.path("title").asText("");
                String snippet = result.path("snippet").asText("");
                if (!matchesExpectedResult(result, link, companyName, true)) {
                    continue;
                }
                Integer count = extractEmployeeCount(title + " " + snippet).orElse(null);
                if (count != null) {
                    return Optional.of(new EmployeeCountEvidence(count, link, title, snippet));
                }
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> extractClaimedEmployeeCount(String text) {
        Matcher matcher = CLAIMED_EMPLOYEE_PATTERN.matcher(text);
        if (matcher.find()) {
            return parseInt(matcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<Integer> extractEmployeeCount(String text) {
        Matcher rangeMatcher = EMPLOYEE_RANGE_PATTERN.matcher(text == null ? "" : text);
        if (rangeMatcher.find()) {
            return parseInt(rangeMatcher.group(2));
        }
        Matcher countMatcher = EMPLOYEE_COUNT_PATTERN.matcher(text == null ? "" : text);
        if (countMatcher.find()) {
            return parseInt(countMatcher.group(1));
        }
        return Optional.empty();
    }

    private Optional<Integer> parseInt(String value) {
        try {
            return Optional.of(Integer.parseInt(value.replaceAll("[^0-9]", "")));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String searchFirstOrganicLink(String query, String companyName, boolean linkedinOnly) {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of("q", query));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SERPER_SEARCH_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-API-KEY", serperApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode organic = root.path("organic");
            if (!organic.isArray() || organic.isEmpty()) {
                return null;
            }

            for (JsonNode result : organic) {
                String link = result.path("link").asText(null);
                if (link == null || link.isBlank()) {
                    continue;
                }
                if (!matchesExpectedResult(result, link.trim(), companyName, linkedinOnly)) {
                    continue;
                }
                return link.trim();
            }

            return null;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private boolean matchesExpectedResult(JsonNode result, String link, String companyName, boolean linkedinOnly) {
        String lowerLink = link.toLowerCase(Locale.ROOT);
        if (linkedinOnly) {
            if (!lowerLink.contains("linkedin.com/company/")) {
                return false;
            }
        } else if (lowerLink.contains("linkedin.com/")) {
            return false;
        }

        String title = result.path("title").asText("");
        String snippet = result.path("snippet").asText("");
        String combined = (title + " " + snippet + " " + link).toLowerCase(Locale.ROOT);

        String[] tokens = companyName == null ? new String[0] : companyName.toLowerCase(Locale.ROOT).split("\\s+");
        String normalizedCompany = normalizeTokenString(companyName);
        String normalizedCombined = normalizeTokenString(combined);

        long tokenCount = Arrays.stream(tokens).filter(token -> token.length() > 2).count();
        long matchedTokens = Arrays.stream(tokens)
                .filter(token -> token.length() > 2)
                .filter(token -> normalizedCombined.contains(normalizeTokenString(token)))
                .count();

        long requiredMatches = Math.max(1, tokenCount / 2);
        if (matchedTokens < requiredMatches) {
            return false;
        }

        if (linkedinOnly) {
            String slug = extractLinkedinCompanySlug(lowerLink);
            if (slug == null || slug.isBlank()) {
                return false;
            }

            String normalizedSlug = normalizeTokenString(slug.replace('-', ' '));
            if (!normalizedSlug.contains(normalizedCompany) && !normalizedCompany.contains(normalizedSlug)) {
                String companyInitials = Arrays.stream(tokens)
                        .filter(token -> token.length() > 2)
                        .map(token -> token.substring(0, 1))
                        .collect(Collectors.joining());
                if (companyInitials.isBlank() || !normalizedSlug.contains(companyInitials.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
        }

        return true;
    }

    private String extractLinkedinCompanySlug(String link) {
        int companyIndex = link.indexOf("/company/");
        if (companyIndex < 0) {
            return null;
        }

        String slugPart = link.substring(companyIndex + "/company/".length());
        int endIndex = slugPart.indexOf('/');
        return endIndex >= 0 ? slugPart.substring(0, endIndex) : slugPart;
    }

    private String normalizeTokenString(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    public record EmployeeCountEvidence(Integer employeeCount, String url, String title, String snippet) {
    }
}
