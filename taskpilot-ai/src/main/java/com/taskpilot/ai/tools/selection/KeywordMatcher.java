package com.taskpilot.ai.tools.selection;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

public class KeywordMatcher {

    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "")
            .replace("đ", "d")
            .replace("Đ", "D")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    public static boolean matches(String normalizedText, String keyword) {
        if (normalizedText == null || keyword == null || normalizedText.isBlank() || keyword.isBlank()) {
            return false;
        }
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return false;
        }

        // Token boundary regex
        String patternString = "\\b" + Pattern.quote(normalizedKeyword) + "\\b";
        Pattern pattern = Pattern.compile(patternString);
        return pattern.matcher(normalizedText).find();
    }

    public static boolean matchesAny(String normalizedText, Collection<String> keywords) {
        if (normalizedText == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (matches(normalizedText, kw)) {
                return true;
            }
        }
        return false;
    }

    public static int countMatches(String normalizedText, Collection<String> keywords) {
        if (normalizedText == null || keywords == null || keywords.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String kw : keywords) {
            if (matches(normalizedText, kw)) {
                count++;
            }
        }
        return count;
    }
}
