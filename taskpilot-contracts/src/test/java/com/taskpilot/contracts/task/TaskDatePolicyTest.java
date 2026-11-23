package com.taskpilot.contracts.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class TaskDatePolicyTest {

    @Test
    void dateOnlyResolvesInProjectTimezoneForStartAndInclusiveMicrosecondDue() {
        Instant start = TaskDatePolicy.parseTaskDate("2026-11-15", "Asia/Ho_Chi_Minh", TaskDateField.START);
        Instant due = TaskDatePolicy.parseTaskDate("2026-11-15", "Asia/Ho_Chi_Minh", TaskDateField.DUE);

        assertEquals(Instant.parse("2026-11-14T17:00:00Z"), start);
        assertEquals(Instant.parse("2026-11-15T16:59:59.999999Z"), due);
        assertDoesNotThrow(() -> TaskDatePolicy.validateEffectiveRange(start, due));
    }

    @Test
    void explicitInstantsAreNeverShiftedAcrossZones() {
        Instant hcm = TaskDatePolicy.parseTaskDate("2026-11-15T10:30:00+02:00", "Asia/Ho_Chi_Minh", TaskDateField.DUE);
        Instant utc = TaskDatePolicy.parseTaskDate("2026-11-15T08:30:00Z", "America/New_York", TaskDateField.DUE);

        assertEquals(Instant.parse("2026-11-15T08:30:00Z"), hcm);
        assertEquals(hcm, utc);
    }

    @Test
    void supportsAmericaNewYorkSpringForwardAndFallBackDstBoundaries() {
        // Spring forward (23-hour day in NY: EST -05:00 -> EDT -04:00)
        Instant springStart = TaskDatePolicy.parseTaskDate("2026-03-08", "America/New_York", TaskDateField.START);
        Instant springDue = TaskDatePolicy.parseTaskDate("2026-03-08", "America/New_York", TaskDateField.DUE);
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), springStart);
        assertEquals(Instant.parse("2026-03-09T03:59:59.999999Z"), springDue);

        // Fall back (25-hour day in NY: EDT -04:00 -> EST -05:00)
        Instant fallStart = TaskDatePolicy.parseTaskDate("2026-11-01", "America/New_York", TaskDateField.START);
        Instant fallDue = TaskDatePolicy.parseTaskDate("2026-11-01", "America/New_York", TaskDateField.DUE);
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), fallStart);
        assertEquals(Instant.parse("2026-11-02T04:59:59.999999Z"), fallDue);
    }

    @Test
    void independentOfHostJvmDefaultTimezone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Clock clock = Clock.fixed(Instant.parse("2026-11-14T18:30:00Z"), ZoneId.of("UTC"));
            // 2026-11-14T18:30:00Z is 2026-11-15 01:30 in Asia/Ho_Chi_Minh (+07:00), still
            // 2026-11-14 in LA
            assertTrue(
                    TaskDatePolicy.isDueToday(Instant.parse("2026-11-15T16:59:59.999999Z"), "Asia/Ho_Chi_Minh", clock));
            assertFalse(TaskDatePolicy.isDueToday(Instant.parse("2026-11-15T17:00:00Z"), "Asia/Ho_Chi_Minh", clock));
            assertEquals(
                    Instant.parse("2026-11-14T17:00:00Z"),
                    TaskDatePolicy.parseTaskDate("2026-11-15", "Asia/Ho_Chi_Minh", TaskDateField.START));
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void validatesRangeOverdueAndZoneId() {
        Instant due = Instant.parse("2026-11-15T00:00:00Z");
        Instant laterStart = Instant.parse("2026-11-15T00:00:01Z");
        assertThrows(IllegalArgumentException.class, () -> TaskDatePolicy.validateEffectiveRange(laterStart, due));

        Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:01Z"), ZoneId.of("UTC"));
        assertTrue(TaskDatePolicy.isOverdue(due, "IN_PROGRESS", clock));
        assertFalse(TaskDatePolicy.isOverdue(due, "DONE", clock));
        assertFalse(TaskDatePolicy.isOverdue(due, "COMPLETED", clock));
        assertFalse(TaskDatePolicy.isOverdue(laterStart, "TODO", clock));

        assertNull(TaskDatePolicy.parseTaskDate((String) null, "UTC", TaskDateField.DUE));
        assertEquals("UTC", TaskDatePolicy.validateZoneId("UTC").getId());
        assertEquals("Asia/Ho_Chi_Minh", TaskDatePolicy.validateZoneId("Asia/Ho_Chi_Minh").getId());
        assertEquals("America/New_York", TaskDatePolicy.validateZoneId("America/New_York").getId());
        for (String unsupportedZone : new String[] { "Z", "UTC+07:00", "+07:00", "GMT+07:00", "Mars/Olympus_Mons",
                "   " }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TaskDatePolicy.validateZoneId(unsupportedZone),
                    "Should reject non-IANA/offset timezone: " + unsupportedZone);
        }
        assertThrows(IllegalArgumentException.class,
                () -> TaskDatePolicy.parseTaskDate("2026-11-15", "Z", TaskDateField.DUE));
        assertThrows(IllegalArgumentException.class,
                () -> TaskDatePolicy.parseTaskDate("2026-11-15", "UTC+07:00", TaskDateField.DUE));
    }
}
