package com.taskpilot.infrastructure.storage.range;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * RFC 9110 compliant parser for single-range HTTP Range and If-Range headers.
 * Multi-range requests are ignored per TaskPilot policy and fall back to full 200 responses.
 */
public final class RangeParser {

    private static final String BYTES_PREFIX = "bytes=";

    private RangeParser() {
    }

    public static RangeParseResult parse(String rangeHeader, long totalBytes) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return RangeParseResult.none(totalBytes);
        }

        String trimmed = rangeHeader.trim();
        if (trimmed.length() < BYTES_PREFIX.length()
                || !trimmed.substring(0, BYTES_PREFIX.length()).toLowerCase(Locale.ROOT).equals(BYTES_PREFIX)) {
            return RangeParseResult.unsatisfiable(totalBytes);
        }

        String specSet = trimmed.substring(BYTES_PREFIX.length()).trim();
        if (specSet.isEmpty()) {
            return RangeParseResult.unsatisfiable(totalBytes);
        }

        if (specSet.contains(",")) {
            String[] parts = specSet.split(",", -1);
            for (String part : parts) {
                if (!isSyntacticallyValidSingleSpec(part.trim())) {
                    return RangeParseResult.unsatisfiable(totalBytes);
                }
            }
            // Unsupported multi-range policy: ignore Range, serve normal 200.
            return RangeParseResult.none(totalBytes);
        }

        return parseSingleSpec(specSet, totalBytes);
    }

    public static boolean matchesIfRange(
            String ifRangeHeader,
            Optional<String> strongEtag,
            Optional<Instant> lastModified
    ) {
        if (ifRangeHeader == null || ifRangeHeader.isBlank()) {
            return true;
        }
        String trimmed = ifRangeHeader.trim();
        if (trimmed.startsWith("W/") || trimmed.startsWith("w/")) {
            return false;
        }
        if (trimmed.startsWith("\"")) {
            return strongEtag != null
                    && strongEtag.isPresent()
                    && !strongEtag.get().startsWith("W/")
                    && !strongEtag.get().startsWith("w/")
                    && strongEtag.get().equals(trimmed);
        }
        if (lastModified == null || lastModified.isEmpty()) {
            return false;
        }
        try {
            ZonedDateTime parsed = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            Instant requested = parsed.toInstant().truncatedTo(ChronoUnit.SECONDS);
            Instant actual = lastModified.get().truncatedTo(ChronoUnit.SECONDS);
            return requested.equals(actual);
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean isSyntacticallyValidSingleSpec(String spec) {
        if (spec.isEmpty()) {
            return false;
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return false;
        }
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        if (startStr.isEmpty() && endStr.isEmpty()) {
            return false;
        }
        if (!isDigitsOrEmpty(startStr) || !isDigitsOrEmpty(endStr)) {
            return false;
        }
        try {
            if (startStr.isEmpty()) {
                return Long.parseLong(endStr) > 0;
            }
            long start = Long.parseLong(startStr);
            if (endStr.isEmpty()) {
                return start >= 0;
            }
            long end = Long.parseLong(endStr);
            return end >= start;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static RangeParseResult parseSingleSpec(String spec, long totalBytes) {
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return RangeParseResult.unsatisfiable(totalBytes);
        }
        String startStr = spec.substring(0, dash).trim();
        String endStr = spec.substring(dash + 1).trim();
        if (startStr.isEmpty() && endStr.isEmpty()) {
            return RangeParseResult.unsatisfiable(totalBytes);
        }
        if (!isDigitsOrEmpty(startStr) || !isDigitsOrEmpty(endStr)) {
            return RangeParseResult.unsatisfiable(totalBytes);
        }

        try {
            if (startStr.isEmpty()) {
                long suffixLength = Long.parseLong(endStr);
                if (suffixLength <= 0 || totalBytes <= 0) {
                    return RangeParseResult.unsatisfiable(totalBytes);
                }
                long start = Math.max(0L, totalBytes - suffixLength);
                long end = totalBytes - 1L;
                return RangeParseResult.satisfiable(new ByteRange(start, end), totalBytes);
            }

            long start = Long.parseLong(startStr);
            if (endStr.isEmpty()) {
                if (start < 0 || totalBytes <= 0 || start >= totalBytes) {
                    return RangeParseResult.unsatisfiable(totalBytes);
                }
                return RangeParseResult.satisfiable(new ByteRange(start, totalBytes - 1L), totalBytes);
            }

            long end = Long.parseLong(endStr);
            if (start < 0 || end < start || totalBytes <= 0 || start >= totalBytes) {
                return RangeParseResult.unsatisfiable(totalBytes);
            }
            long clampedEnd = Math.min(end, totalBytes - 1L);
            return RangeParseResult.satisfiable(new ByteRange(start, clampedEnd), totalBytes);
        } catch (NumberFormatException ex) {
            return RangeParseResult.unsatisfiable(totalBytes);
        }
    }

    private static boolean isDigitsOrEmpty(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }
}
