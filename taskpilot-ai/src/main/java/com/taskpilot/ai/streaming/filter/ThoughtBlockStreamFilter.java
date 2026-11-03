package com.taskpilot.ai.streaming.filter;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful stream filter that strips model reasoning blocks (<thought>, <think>, <dthink>)
 * across Server-Sent Events (SSE) token chunks, preventing thought leaks to the user interface.
 */
public class ThoughtBlockStreamFilter {

    private static final String[] TAG_NAMES = {"thought", "think", "dthink"};
    private static final Pattern OPEN_TAG_PATTERN = Pattern.compile(
        "^<\\s*(thought|think|dthink)(\\s+[^>]*)?>",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CLOSE_TAG_PATTERN = Pattern.compile(
        "^<\\s*/\\s*(thought|think|dthink)\\s*>",
        Pattern.CASE_INSENSITIVE
    );

    private boolean insideThought = false;
    private final StringBuilder pendingBuffer = new StringBuilder();
    private final StringBuilder hiddenReasoning = new StringBuilder();
    private final StringBuilder visibleResponse = new StringBuilder();

    public synchronized String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }

        pendingBuffer.append(chunk);
        StringBuilder output = new StringBuilder();

        while (pendingBuffer.length() > 0) {
            if (!insideThought) {
                int openIndex = pendingBuffer.indexOf("<");
                if (openIndex == -1) {
                    // No tag starting character in buffer
                    output.append(pendingBuffer);
                    visibleResponse.append(pendingBuffer);
                    pendingBuffer.setLength(0);
                    break;
                }

                if (openIndex > 0) {
                    String prefix = pendingBuffer.substring(0, openIndex);
                    output.append(prefix);
                    visibleResponse.append(prefix);
                    pendingBuffer.delete(0, openIndex);
                }

                // pendingBuffer now starts with '<'
                Matcher openMatcher = OPEN_TAG_PATTERN.matcher(pendingBuffer);
                if (openMatcher.find()) {
                    // Complete opening tag found
                    insideThought = true;
                    pendingBuffer.delete(0, openMatcher.end());
                } else if (isPotentialOpeningTag(pendingBuffer.toString())) {
                    // Buffer may contain an incomplete opening tag split across chunks -> wait for next chunk
                    break;
                } else {
                    // It's just a '<' that is NOT an opening tag (e.g. "a < b" or "<br>")
                    output.append('<');
                    visibleResponse.append('<');
                    pendingBuffer.deleteCharAt(0);
                }
            } else {
                // Currently inside thought block
                int closeIndex = pendingBuffer.indexOf("<");
                if (closeIndex == -1) {
                    hiddenReasoning.append(pendingBuffer);
                    pendingBuffer.setLength(0);
                    break;
                }

                if (closeIndex > 0) {
                    hiddenReasoning.append(pendingBuffer.substring(0, closeIndex));
                    pendingBuffer.delete(0, closeIndex);
                }

                // pendingBuffer starts with '<'
                Matcher closeMatcher = CLOSE_TAG_PATTERN.matcher(pendingBuffer);
                if (closeMatcher.find()) {
                    insideThought = false;
                    pendingBuffer.delete(0, closeMatcher.end());
                } else if (isPotentialClosingTag(pendingBuffer.toString())) {
                    // Buffer may contain an incomplete closing tag split across chunks
                    break;
                } else {
                    hiddenReasoning.append('<');
                    pendingBuffer.deleteCharAt(0);
                }
            }
        }

        return output.toString();
    }

    public synchronized String flush() {
        StringBuilder output = new StringBuilder();
        if (pendingBuffer.length() > 0) {
            if (!insideThought) {
                output.append(pendingBuffer);
                visibleResponse.append(pendingBuffer);
            } else {
                hiddenReasoning.append(pendingBuffer);
            }
            pendingBuffer.setLength(0);
        }
        return output.toString();
    }

    public synchronized String getHiddenReasoning() {
        return hiddenReasoning.toString();
    }

    public synchronized String getVisibleResponse() {
        return visibleResponse.toString();
    }

    public synchronized boolean isInsideThought() {
        return insideThought;
    }

    private boolean isPotentialOpeningTag(String s) {
        if (!s.startsWith("<")) {
            return false;
        }
        String clean = s.substring(1).trim().toLowerCase(Locale.ROOT);
        for (String name : TAG_NAMES) {
            if (name.startsWith(clean) || clean.startsWith(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPotentialClosingTag(String s) {
        if (!s.startsWith("<")) {
            return false;
        }
        String clean = s.substring(1).trim().toLowerCase(Locale.ROOT);
        if (clean.isEmpty() || clean.equals("/")) {
            return true;
        }
        if (clean.startsWith("/")) {
            String tagPart = clean.substring(1).trim();
            for (String name : TAG_NAMES) {
                if (name.startsWith(tagPart) || tagPart.startsWith(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
