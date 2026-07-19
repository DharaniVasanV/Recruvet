package com.fakejobpostsystem.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fakejobpostsystem.dto.Entities;

@Service
public class EntityExtractionService {

    private static final Pattern LEGAL_SUFFIX_PATTERN = Pattern.compile(
            "(?i)\\b(?:private limited|private ltd|pvt\\.?\\s*ltd\\.?|pvt\\.?\\s*limited|limited|ltd\\.?|llc|inc\\.?|corp\\.?|corporation|company)\\b\\s*$");

    private static final List<String> FREE_EMAIL_DOMAINS = List.of("gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "protonmail.com");
    private static final List<String> COMMON_LOCAL_PARTS = List.of("hello", "hr", "careers", "jobs", "info", "support", "contact", "recruitment", "recruiter");
    private static final List<String> ORG_STOP_WORDS = List.of(
            "after", "careful", "consideration", "assessment", "performance", "regards",
            "thanks", "sincerely", "hello", "dear", "job", "role", "position",
            "this", "that", "you", "your", "they", "them", "team", "internship",
            "candidate", "recommendation", "letter", "anywhere", "street", "city",
            "concern", "program", "opportunity", "collaborations");

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?:\\+?\\d[\\d\\s().-]{8,}\\d)|(?:\\b\\d{10}\\b)");
    private static final Pattern DIRECT_EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+\\s*@\\s*[A-Za-z0-9.-]+\\s*\\.\\s*[A-Za-z]{2,}");
    private static final Pattern COMPACT_EMAIL_PATTERN = Pattern.compile("\\S+@\\S+");
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("(?:(?:www\\.)|(?:[A-Za-z0-9._%+-]+\\s*@\\s*))\\s*([A-Za-z0-9-]+\\s*\\.\\s*(?:com|in|org|net|co))", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_PART_PATTERN = Pattern.compile("\\b(?:hello|hr|careers|jobs|info|support|contact|recruitment|recruiter)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOISY_TOKEN_PATTERN = Pattern.compile("\\b[a-zA-Z0-9@._-]{8,}\\b");
    private static final Pattern PERSON_CONTEXT_PATTERN = Pattern.compile("(?i)(?:contact|reach out to|recruiter|hiring manager|hr|hr manager|point of contact|letter to)\\s*[:\\-]?\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2})");
    private static final Pattern PERSON_SIGNATURE_PATTERN = Pattern.compile("(?m)^(?:regards|thanks|sincerely|best),?\\s*\\n?\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2})$");
    private static final Pattern PERSON_ROLE_PATTERN = Pattern.compile("\\b(HR Manager|Hiring Manager|Recruiter|Talent Acquisition|Human Resources)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECIPIENT_BLOCK_PATTERN = Pattern.compile("(?im)^letter to\\s*:?\\s*$\\s*([A-Z][a-z]+\\s+[A-Z][a-z]+)");
    private static final Pattern PERSON_NAME_PATTERN = Pattern.compile("\\b([A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,}){1,2})\\b");
    private static final Pattern ORG_SUFFIX_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9&'-]*(?:\\s+[A-Z][A-Za-z0-9&'-]*){0,3}\\s(?:Inc|LLC|Ltd|Corp|Company|Technologies|Solutions|Labs|Systems|Services|Fintech|Capital|Ventures|Consulting|Software|University|College|Institute|Partners))\\b");
    private static final Pattern ORG_CONTEXT_PATTERN = Pattern.compile("(?im)(?:company|organization|employer|university|college|institute)\\s*[:\\-]\\s*([A-Z][A-Za-z0-9&'-]*(?:\\s+[A-Z][A-Za-z0-9&'-]*){0,3})");
    private static final Pattern ORG_STANDALONE_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+){0,2})\\b");
    private static final Pattern HEADER_COMPANY_PATTERN = Pattern.compile("(?m)^\\s*([A-Z][A-Za-z0-9&'-]*(?:\\s+[A-Z][A-Za-z0-9&'-]*){0,3}\\s(?:Fintech|Technologies|Solutions|Labs|Systems|Services|Company|Inc|LLC|Ltd|Corp|Capital|Ventures|Software|University|College|Institute|Partners))\\s*$");
    private static final Pattern UNIVERSITY_HEADER_PATTERN = Pattern.compile("(?m)^\\s*([A-Z][A-Z\\s]{4,}(?:UNIVERSITY|COLLEGE|INSTITUTE))\\s*$");
    private static final Pattern AMPERSAND_ORG_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)*\\s+and\\s+[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)*)\\b");

    public Entities extract(String text) {
        String safeText = text == null ? "" : text;

        Set<String> phones = extractPhones(safeText);
        Set<String> emails = extractEmails(safeText);
        Set<String> persons = extractPersons(safeText);
        Set<String> organizations = extractOrganizations(safeText, emails);

        return new Entities(List.copyOf(phones), List.copyOf(emails), List.copyOf(persons), List.copyOf(organizations));
    }

    private Set<String> extractPhones(String text) {
        LinkedHashSet<String> phones = new LinkedHashSet<>();
        Matcher matcher = PHONE_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().replaceAll("\\s+", " ").trim();
            if (!value.isBlank()) {
                phones.add(value);
            }
        }
        return phones;
    }

    private Set<String> extractEmails(String text) {
        LinkedHashSet<String> emails = new LinkedHashSet<>();

        collectEmails(DIRECT_EMAIL_PATTERN, text, emails);
        collectEmails(COMPACT_EMAIL_PATTERN, text, emails);

        Set<String> domains = new LinkedHashSet<>();
        Matcher domainMatcher = DOMAIN_PATTERN.matcher(text);
        while (domainMatcher.find()) {
            String domain = domainMatcher.group(1).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
            if (domain.contains(".") && domain.length() >= 6) {
                domains.add(domain);
            }
        }

        Set<String> localParts = new LinkedHashSet<>();
        Matcher localPartMatcher = LOCAL_PART_PATTERN.matcher(text);
        while (localPartMatcher.find()) {
            localParts.add(localPartMatcher.group().toLowerCase(Locale.ROOT));
        }

        Matcher noisyTokenMatcher = NOISY_TOKEN_PATTERN.matcher(text);
        while (noisyTokenMatcher.find()) {
            String normalized = normalizeOcrToken(noisyTokenMatcher.group());
            String cleaned = cleanEmail(normalized);
            if (cleaned != null) {
                emails.add(cleaned);
                continue;
            }

            Matcher domainInToken = Pattern.compile("([a-z0-9-]+\\.(?:com|in|org|net|co))").matcher(normalized);
            while (domainInToken.find()) {
                String domain = domainInToken.group(1);
                for (String localPart : COMMON_LOCAL_PARTS) {
                    if (normalized.startsWith(localPart) && !looksGenericDomain(domain)) {
                        String candidate = cleanEmail(localPart + "@" + domain);
                        if (candidate != null) {
                            emails.add(candidate);
                        }
                    }
                }
            }
        }

        return emails;
    }

    private void collectEmails(Pattern pattern, String text, Set<String> target) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String cleaned = cleanEmail(matcher.group());
            if (cleaned != null) {
                target.add(cleaned);
            }
        }
    }

    private Set<String> extractPersons(String text) {
        LinkedHashSet<String> persons = new LinkedHashSet<>();
        collectCaptures(PERSON_CONTEXT_PATTERN, text, persons);
        collectCaptures(PERSON_SIGNATURE_PATTERN, text, persons);
        collectCaptures(RECIPIENT_BLOCK_PATTERN, text, persons);
        collectPersonNames(text, persons);

        Matcher roleMatcher = PERSON_ROLE_PATTERN.matcher(text);
        while (roleMatcher.find()) {
            String role = roleMatcher.group(1).replaceAll("\\s+", " ").trim();
            if (!role.isBlank()) {
                persons.add(role);
            }
        }
        return persons;
    }

    private void collectPersonNames(String text, Set<String> target) {
        Matcher matcher = PERSON_NAME_PATTERN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).replaceAll("\\s+", " ").trim();
            if (looksLikePersonName(candidate)) {
                target.add(candidate);
            }
        }
    }

    private Set<String> extractOrganizations(String text, Set<String> emails) {
        LinkedHashSet<String> organizations = new LinkedHashSet<>();
        collectOrganizations(ORG_SUFFIX_PATTERN, text, organizations);
        collectOrganizations(ORG_CONTEXT_PATTERN, text, organizations);
        collectOrganizations(AMPERSAND_ORG_PATTERN, text, organizations);
        collectOrganizations(ORG_STANDALONE_PATTERN, text, organizations);
        collectAllCapsOrganizations(UNIVERSITY_HEADER_PATTERN, text, organizations);

        for (String email : emails) {
            int atIndex = email.indexOf('@');
            if (atIndex < 0 || atIndex == email.length() - 1) {
                continue;
            }

            String domain = email.substring(atIndex + 1).toLowerCase(Locale.ROOT);
            if (FREE_EMAIL_DOMAINS.contains(domain) || looksGenericDomain(domain)) {
                continue;
            }
            String company = domain.split("\\.")[0];
            if (!company.isBlank()) {
                organizations.add(Character.toUpperCase(company.charAt(0)) + company.substring(1));
            }
        }

        return organizations;
    }

    private void collectCaptures(Pattern pattern, String text, Set<String> target) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1).replaceAll("\\s+", " ").trim();
            if (!value.isBlank()) {
                target.add(value);
            }
        }
    }

    private void collectOrganizations(Pattern pattern, String text, Set<String> target) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String cleaned = sanitizeOrganization(matcher.group(1));
            if (cleaned != null) {
                target.add(cleaned);
            }
        }
    }

    private void collectAllCapsOrganizations(Pattern pattern, String text, Set<String> target) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim().replaceAll("\\s+", " ");
            String cleaned = sanitizeOrganization(toTitleCase(candidate.toLowerCase(Locale.ROOT)));
            if (cleaned != null) {
                target.add(cleaned);
            }
        }
    }

    private String cleanEmail(String value) {
        if (value == null) {
            return null;
        }

        String cleaned = value
                .replace(" ", "")
                .replace("(at)", "@")
                .replace("[at]", "@")
                .replace("(dot)", ".")
                .replace("[dot]", ".")
                .replace("|", "l")
                .replaceAll("[,;:]+$", "")
                .trim()
                .toLowerCase(Locale.ROOT);

        cleaned = cleaned.replaceAll("(?<=\\w)[gq9](?=[a-z0-9-]+\\.(?:com|in|org|net|co)\\b)", "@");
        cleaned = cleaned.replaceAll("(?<=\\w)([a-z0-9-]+)(com|in|org|net|co)\\b", "$1.$2");

        if (cleaned.matches("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}") && !looksGenericDomain(cleaned.substring(cleaned.indexOf('@') + 1))) {
            return cleaned;
        }
        return null;
    }

    private String normalizeOcrToken(String token) {
        return token.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("|", "l")
                .replace("wwe", "www")
                .replace("vvww", "www")
                .replace("(at)", "@")
                .replace("[at]", "@")
                .replace("(dot)", ".")
                .replace("[dot]", ".");
    }

    private String sanitizeOrganization(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String cleaned = rawValue
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[,.;:]+$", "")
                .trim();
        if (cleaned.isBlank()) {
            return null;
        }

        String[] tokens = cleaned.split("\\s+");
        if (tokens.length > 4) {
            return null;
        }
        if (tokens.length == 1 && cleaned.length() <= 2) {
            return null;
        }
        if (cleaned.matches("[A-Z]{1,3}")) {
            return null;
        }
        if (looksLikePersonName(cleaned)) {
            return null;
        }
        String lowerCleaned = cleaned.toLowerCase(Locale.ROOT);
        if (lowerCleaned.contains("manager") || lowerCleaned.contains("recruiter") || lowerCleaned.contains("human resources")) {
            return null;
        }

        String lower = lowerCleaned;
        for (String stopWord : ORG_STOP_WORDS) {
            if (lower.equals(stopWord) || lower.startsWith(stopWord + " ") || lower.endsWith(" " + stopWord)) {
                return null;
            }
        }

        boolean hasOrgSuffix = cleaned.matches(".*(?:Inc|LLC|Ltd|Corp|Company|Technologies|Solutions|Labs|Systems|Services|Fintech|Capital|Ventures|Consulting|Software|University|College|Institute|Partners)$");
        boolean titleCaseWords = cleaned.matches("[A-Z][a-zA-Z0-9&'-]*(?:\\s+[A-Z][a-zA-Z0-9&'-]*){0,2}");
        boolean looksLikeOrg = hasOrgSuffix || (titleCaseWords && tokens.length >= 2);
        return looksLikeOrg ? cleaned : null;
    }

    public String inferCompanyNameFromEmails(List<String> emails) {
        if (emails == null) {
            return null;
        }

        for (String email : emails) {
            int atIndex = email.indexOf('@');
            if (atIndex < 0 || atIndex == email.length() - 1) {
                continue;
            }

            String domain = email.substring(atIndex + 1).toLowerCase(Locale.ROOT);
            if (FREE_EMAIL_DOMAINS.contains(domain) || looksGenericDomain(domain)) {
                continue;
            }

            String company = domain.split("\\.")[0].replace('-', ' ').trim();
            if (!company.isBlank() && company.length() > 2) {
                return normalizeCompanyDisplayName(toTitleCase(company));
            }
        }
        return null;
    }

    public String inferCompanyNameFromText(String text, Entities entities) {
        String safeText = text == null ? "" : text;

        Matcher headerMatcher = HEADER_COMPANY_PATTERN.matcher(safeText);
        while (headerMatcher.find()) {
            String cleaned = sanitizeOrganization(headerMatcher.group(1));
            if (cleaned != null) {
                return normalizeCompanyDisplayName(cleaned);
            }
        }

        if (entities == null || entities.organizations() == null || entities.organizations().isEmpty()) {
            return null;
        }

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String organization : entities.organizations()) {
            String cleaned = sanitizeOrganization(organization);
            if (cleaned == null) {
                continue;
            }

            int score = companyScore(cleaned, safeText);
            if (score > bestScore) {
                bestScore = score;
                best = normalizeCompanyDisplayName(cleaned);
            }
        }
        return best;
    }

    public String normalizeCompanyDisplayName(String companyName) {
        if (companyName == null) {
            return null;
        }

        String cleaned = companyName.replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()) {
            return null;
        }

        String previous;
        do {
            previous = cleaned;
            cleaned = LEGAL_SUFFIX_PATTERN.matcher(cleaned).replaceFirst("").trim();
        } while (!cleaned.equals(previous));

        cleaned = cleaned.replaceAll("[,.-]+$", "").trim();
        return cleaned.isBlank() ? companyName.trim() : cleaned;
    }

    public List<String> refineContactPersons(String text, List<String> persons) {
        if (persons == null || persons.isEmpty()) {
            return List.of();
        }

        String safeText = text == null ? "" : text;
        List<String> refined = persons.stream()
                .map(this::sanitizePerson)
                .filter(person -> person != null && isLikelyContactPerson(safeText, person))
                .distinct()
                .collect(Collectors.toList());

        if (!refined.isEmpty()) {
            return refined;
        }

        return persons.stream()
                .map(this::sanitizePerson)
                .filter(person -> person != null && !isLikelyRecipientOrApplicant(safeText, person))
                .distinct()
                .collect(Collectors.toList());
    }

    private int companyScore(String company, String text) {
        int score = 0;
        String lowerCompany = company.toLowerCase(Locale.ROOT);
        String lowerText = text.toLowerCase(Locale.ROOT);

        if (company.matches(".*(?:Inc|LLC|Ltd|Corp|Company|Technologies|Solutions|Labs|Systems|Services|Fintech|Capital|Ventures|Consulting|Software|University|College|Institute|Partners)$")) {
            score += 5;
        }
        if (company.split("\\s+").length >= 2) {
            score += 3;
        }
        if (lowerText.startsWith(lowerCompany) || lowerText.contains("\n" + lowerCompany)) {
            score += 4;
        }

        int occurrences = 0;
        int index = lowerText.indexOf(lowerCompany);
        while (index >= 0) {
            occurrences++;
            index = lowerText.indexOf(lowerCompany, index + lowerCompany.length());
        }
        score += occurrences * 2;

        if (company.length() <= 2 || company.matches("[A-Z]{1,3}")) {
            score -= 10;
        }
        if (lowerCompany.contains("recommendation") || lowerCompany.contains("anywhere")) {
            score -= 8;
        }
        return score;
    }

    private String sanitizePerson(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
        return looksLikePersonName(cleaned) ? cleaned : null;
    }

    private boolean isLikelyContactPerson(String text, String person) {
        String lowerText = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String lowerPerson = person.toLowerCase(Locale.ROOT);

        int index = lowerText.indexOf(lowerPerson);
        boolean found = false;
        int bestScore = Integer.MIN_VALUE;
        while (index >= 0) {
            found = true;
            int start = Math.max(0, index - 80);
            int end = Math.min(lowerText.length(), index + lowerPerson.length() + 80);
            String window = lowerText.substring(start, end);
            int score = 0;

            if (window.contains("contact") || window.contains("reach out") || window.contains("hr")
                    || window.contains("recruiter") || window.contains("hiring manager")
                    || window.contains("manager") || window.contains("human resources")
                    || window.contains("professor") || window.contains("regards")
                    || window.contains("sincerely") || window.contains("best regards")
                    || window.contains("signature") || window.contains("signatory")
                    || window.contains("best,") || window.contains("thanks,")
                    || window.contains("from") || window.contains("warm regards")) {
                score += 4;
            }
            if (isLikelyRecipientWindow(window)) {
                score -= 5;
            }

            bestScore = Math.max(bestScore, score);
            index = lowerText.indexOf(lowerPerson, index + lowerPerson.length());
        }

        if (!found) {
            return true;
        }
        return bestScore >= 0;
    }

    private boolean isLikelyRecipientOrApplicant(String text, String person) {
        String lowerText = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String lowerPerson = person.toLowerCase(Locale.ROOT);

        int index = lowerText.indexOf(lowerPerson);
        while (index >= 0) {
            int start = Math.max(0, index - 80);
            int end = Math.min(lowerText.length(), index + lowerPerson.length() + 80);
            String window = lowerText.substring(start, end);
            if (isLikelyRecipientWindow(window)) {
                return true;
            }
            index = lowerText.indexOf(lowerPerson, index + lowerPerson.length());
        }
        return false;
    }

    private boolean isLikelyRecipientWindow(String window) {
        return window.contains("dear ")
                || window.contains("letter to")
                || window.contains("offer you")
                || window.contains("offered to")
                || window.contains("application")
                || window.contains("candidate")
                || window.contains("intern")
                || window.contains("student")
                || window.contains("recipient");
    }

    private boolean looksLikePersonName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }

        String cleaned = value.replaceAll("\\s+", " ").trim();
        if (!cleaned.matches("[A-Z][a-z]{2,}(?:\\s+[A-Z][a-z]{2,}){1,2}")) {
            return false;
        }

        String lower = cleaned.toLowerCase(Locale.ROOT);
        return !lower.contains("technologies")
                && !lower.contains("solutions")
                && !lower.contains("systems")
                && !lower.contains("services")
                && !lower.contains("fintech")
                && !lower.contains("software")
                && !lower.contains("university")
                && !lower.contains("college")
                && !lower.contains("institute")
                && !lower.contains("partners");
    }

    private boolean looksGenericDomain(String domain) {
        String base = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
        return base.startsWith("candidate.")
                || base.startsWith("environment.")
                || base.startsWith("team.")
                || base.startsWith("internship.")
                || base.startsWith("program.");
    }

    private String toTitleCase(String value) {
        String[] parts = value.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }
}
