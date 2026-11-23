package com.taskpilot.infrastructure.storage.range;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class P4StorageRangeTest {

    @Test
    void parse_nullOrBlankReturnsNone() {
        assertThat(RangeParser.parse(null, 100).status()).isEqualTo(RangeParseResult.Status.NONE);
        assertThat(RangeParser.parse("   ", 100).status()).isEqualTo(RangeParseResult.Status.NONE);
    }

    @Test
    void parse_singleBoundedOpenAndSuffixRanges() {
        RangeParseResult bounded = RangeParser.parse("bytes=10-19", 100);
        assertThat(bounded.status()).isEqualTo(RangeParseResult.Status.SATISFIABLE);
        assertThat(bounded.range()).contains(new ByteRange(10, 19));

        RangeParseResult clamped = RangeParser.parse("bytes=80-9999", 100);
        assertThat(clamped.status()).isEqualTo(RangeParseResult.Status.SATISFIABLE);
        assertThat(clamped.range()).contains(new ByteRange(80, 99));

        RangeParseResult openEnded = RangeParser.parse("bytes=90-", 100);
        assertThat(openEnded.status()).isEqualTo(RangeParseResult.Status.SATISFIABLE);
        assertThat(openEnded.range()).contains(new ByteRange(90, 99));

        RangeParseResult suffix = RangeParser.parse("bytes=-15", 100);
        assertThat(suffix.status()).isEqualTo(RangeParseResult.Status.SATISFIABLE);
        assertThat(suffix.range()).contains(new ByteRange(85, 99));

        RangeParseResult suffixLargerThanFile = RangeParser.parse("bytes=-500", 100);
        assertThat(suffixLargerThanFile.status()).isEqualTo(RangeParseResult.Status.SATISFIABLE);
        assertThat(suffixLargerThanFile.range()).contains(new ByteRange(0, 99));
    }

    @Test
    void parse_malformedOrUnsatisfiableReturns416() {
        assertThat(RangeParser.parse("items=0-5", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=-", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=-0", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=20-10", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=100-105", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=100-", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=0-0", 0).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
        assertThat(RangeParser.parse("bytes=abc-def", 100).status()).isEqualTo(RangeParseResult.Status.UNSATISFIABLE);
    }

    @Test
    void parse_validMultiRangeIgnoredPerPolicy() {
        RangeParseResult multi = RangeParser.parse("bytes=0-1, 5-9", 100);
        assertThat(multi.status()).isEqualTo(RangeParseResult.Status.NONE);
    }

    @Test
    void matchesIfRange_checksStrongEtagAndHttpDate() {
        Instant lastMod = Instant.parse("2026-11-16T12:34:56.789Z");
        String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(lastMod, ZoneOffset.UTC));

        assertThat(RangeParser.matchesIfRange(null, Optional.of("\"e1\""), Optional.of(lastMod))).isTrue();
        assertThat(RangeParser.matchesIfRange("\"e1\"", Optional.of("\"e1\""), Optional.of(lastMod))).isTrue();
        assertThat(RangeParser.matchesIfRange("\"e2\"", Optional.of("\"e1\""), Optional.of(lastMod))).isFalse();
        assertThat(RangeParser.matchesIfRange("W/\"e1\"", Optional.of("\"e1\""), Optional.of(lastMod))).isFalse();
        assertThat(RangeParser.matchesIfRange(httpDate, Optional.of("\"e1\""), Optional.of(lastMod))).isTrue();

        String staleDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(lastMod.minusSeconds(60), ZoneOffset.UTC));
        assertThat(RangeParser.matchesIfRange(staleDate, Optional.of("\"e1\""), Optional.of(lastMod))).isFalse();
    }
}
