package com.taskpilot.infrastructure.storage.range;

import java.io.IOException;
import java.util.regex.Pattern;

/** Validates a provider's exact partial response before exposing its stream as HTTP 206. */
public final class ProviderRangeValidator {
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    private ProviderRangeValidator() {
    }

    public static long validate(ByteRange requested, int status, String contentRange, Long contentLength)
            throws IOException {
        var match = CONTENT_RANGE.matcher(contentRange != null ? contentRange.trim() : "");
        if (status != 206 || !match.matches()) {
            throw new IOException("Storage provider did not return the requested partial response");
        }
        try {
            long start = Long.parseLong(match.group(1));
            long end = Long.parseLong(match.group(2));
            long total = Long.parseLong(match.group(3));
            if (start != requested.startInclusive() || end != requested.endInclusive() || total <= end
                    || (contentLength != null && contentLength != requested.length())) {
                throw new IOException("Storage provider returned a mismatched byte range");
            }
            return total;
        } catch (NumberFormatException ex) {
            throw new IOException("Storage provider returned an invalid byte range", ex);
        }
    }
}
