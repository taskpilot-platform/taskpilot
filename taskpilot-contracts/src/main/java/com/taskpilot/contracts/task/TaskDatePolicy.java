package com.taskpilot.contracts.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneRulesException;
import java.util.Set;

/**
 * Canonical project-zone task date conversion and boundary checks shared across REST, projects, and AI.
 */
public final class TaskDatePolicy {

    public static final String DEFAULT_PROJECT_TIMEZONE_ID = "Asia/Ho_Chi_Minh";
    public static final String LEGACY_PROJECT_TIMEZONE_ID = "UTC";

    private TaskDatePolicy() {
    }

    private static final Set<String> AVAILABLE_ZONE_IDS = ZoneId.getAvailableZoneIds();

    public static ZoneId validateZoneId(String timezoneId) {
        if (timezoneId == null || timezoneId.isBlank()) {
            throw new IllegalArgumentException("Project timezoneId cannot be blank");
        }
        String trimmed = timezoneId.trim();
        if (!"UTC".equals(trimmed) && !isCanonicalIanaRegionId(trimmed)) {
            throw new IllegalArgumentException(
                    "Project timezoneId must be 'UTC' or a canonical IANA region ID (e.g. 'Asia/Ho_Chi_Minh'): " + trimmed);
        }
        try {
            return ZoneId.of(trimmed);
        } catch (ZoneRulesException ex) {
            throw new IllegalArgumentException("Invalid IANA timezoneId: " + trimmed, ex);
        }
    }

    private static boolean isCanonicalIanaRegionId(String trimmed) {
        if (!trimmed.contains("/") || !AVAILABLE_ZONE_IDS.contains(trimmed)) {
            return false;
        }
        return !trimmed.startsWith("UTC+") && !trimmed.startsWith("UTC-")
                && !trimmed.startsWith("GMT+") && !trimmed.startsWith("GMT-")
                && !trimmed.startsWith("UT+") && !trimmed.startsWith("UT-");
    }

    public static ZoneId effectiveProjectZone(String projectTimezoneId) {
        if (projectTimezoneId == null || projectTimezoneId.isBlank()) {
            return ZoneId.of(DEFAULT_PROJECT_TIMEZONE_ID);
        }
        return validateZoneId(projectTimezoneId);
    }

    public static Instant parseTaskDate(String raw, String projectTimezoneId, TaskDateField field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (field == null) {
            throw new IllegalArgumentException("TaskDateField is required");
        }
        ZoneId zone = effectiveProjectZone(projectTimezoneId);

        if (isDateOnly(trimmed)) {
            LocalDate date;
            try {
                date = LocalDate.parse(trimmed);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("Invalid date value '" + trimmed + "'. Use YYYY-MM-DD or ISO-8601 instant", ex);
            }
            return switch (field) {
                case START -> date.atStartOfDay(zone).toInstant();
                case DUE -> date.plusDays(1).atStartOfDay(zone).toInstant().minus(1, ChronoUnit.MICROS);
            };
        }

        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // Try explicit offset/zoned ISO-8601 forms
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ignored) {
            // Try zoned ISO-8601
        }
        try {
            return ZonedDateTime.parse(trimmed).toInstant();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Invalid task date '" + trimmed + "'. Use ISO-8601 instant with offset/Z or YYYY-MM-DD", ex);
        }
    }

    public static Instant parseTaskDate(Object value, String projectTimezoneId, TaskDateField field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof LocalDate localDate) {
            return parseTaskDate(localDate.toString(), projectTimezoneId, field);
        }
        return parseTaskDate(value.toString(), projectTimezoneId, field);
    }

    public static void validateEffectiveRange(Instant startDate, Instant dueDate) {
        if (startDate != null && dueDate != null && dueDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Task due date cannot be earlier than start date");
        }
    }

    public static boolean isDueToday(Instant dueAt, String projectTimezoneId, Clock clock) {
        if (dueAt == null || clock == null) {
            return false;
        }
        ZoneId zone = effectiveProjectZone(projectTimezoneId);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant nextDayStart = today.plusDays(1).atStartOfDay(zone).toInstant();
        return !dueAt.isBefore(dayStart) && dueAt.isBefore(nextDayStart);
    }

    public static boolean isOverdue(Instant dueAt, String status, Clock clock) {
        if (dueAt == null || clock == null) {
            return false;
        }
        if ("DONE".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
            return false;
        }
        return dueAt.isBefore(clock.instant());
    }

    private static boolean isDateOnly(String trimmed) {
        return trimmed.length() == 10
                && Character.isDigit(trimmed.charAt(0))
                && trimmed.charAt(4) == '-'
                && trimmed.charAt(7) == '-';
    }
}
