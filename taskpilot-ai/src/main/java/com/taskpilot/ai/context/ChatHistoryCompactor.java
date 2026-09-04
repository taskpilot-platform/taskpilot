package com.taskpilot.ai.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages context window budgeting and history compaction for long-running chat sessions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHistoryCompactor {

    private final TokenCountEstimator tokenCountEstimator;
    private final ChatMessageSanitizer sanitizer;

    @Value("${ai.chat.memory-max-tokens:7000}")
    private int maxContextTokens = 7000;

    @Value("${ai.chat.context-tail-messages:6}")
    private int contextTailMessages = 6;

    @Value("${ai.chat.compact-summary-max-chars:3000}")
    private int compactSummaryMaxChars = 3000;

    @Value("${ai.chat.compact-message-max-chars:600}")
    private int compactMessageMaxChars = 600;

    /**
     * Compacts message history if token count exceeds maxContextTokens.
     */
    public List<ChatMessage> compactHistoryForRequest(List<ChatMessage> messages, String stage) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        int beforeTokens = estimateTokens(messages);
        if (beforeTokens <= maxContextTokens || messages.size() <= 3) {
            return messages;
        }

        ChatMessage primarySystemPrompt = null;
        List<ChatMessage> body = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (primarySystemPrompt == null && message instanceof SystemMessage) {
                primarySystemPrompt = message;
            } else {
                body.add(message);
            }
        }

        int tailSize = Math.min(Math.max(2, contextTailMessages), body.size());
        if (body.size() <= tailSize) {
            return messages;
        }

        List<ChatMessage> older = new ArrayList<>(body.subList(0, body.size() - tailSize));
        List<ChatMessage> tail = new ArrayList<>(body.subList(body.size() - tailSize, body.size()));
        List<ChatMessage> compacted = buildCompactedMessages(primarySystemPrompt, older, tail);

        while (estimateTokens(compacted) > maxContextTokens && tail.size() > 2) {
            older.add(tail.remove(0));
            compacted = buildCompactedMessages(primarySystemPrompt, older, tail);
        }

        int afterTokens = estimateTokens(compacted);
        log.info("[ContextCompaction] stage={} messages {}->{} tokens~{}->{} olderCompacted={} tailKept={}",
                stage, messages.size(), compacted.size(), beforeTokens, afterTokens, older.size(), tail.size());
        return compacted;
    }

    public List<ChatMessage> buildCompactedMessages(
            ChatMessage primarySystemPrompt,
            List<ChatMessage> older,
            List<ChatMessage> tail) {
        List<ChatMessage> compacted = new ArrayList<>();
        if (primarySystemPrompt != null) {
            compacted.add(primarySystemPrompt);
        }
        compacted.addAll(tail);
        return compacted;
    }

    public String buildCompactSummary(List<ChatMessage> olderMessages) {
        StringBuilder summary = new StringBuilder();
        summary.append("[COMPACTED CONVERSATION CONTEXT]\n");
        summary.append("Older messages were compacted to keep the request context small. ");
        summary.append("Use this only as continuity memory; call tools again when current data is needed.\n\n");

        int omitted = 0;
        for (int i = 0; i < olderMessages.size(); i++) {
            ChatMessage message = olderMessages.get(i);
            String line = "- " + compactRole(message) + ": "
                    + compactText(sanitizer.messageText(message), compactMessageMaxChars) + "\n";

            if (summary.length() + line.length() > compactSummaryMaxChars) {
                omitted = olderMessages.size() - i;
                break;
            }
            summary.append(line);
        }

        if (omitted > 0) {
            summary.append("- [").append(omitted).append(" older messages omitted]\n");
        }
        return summary.toString();
    }

    public int estimateTokens(List<ChatMessage> messages) {
        try {
            return tokenCountEstimator.estimateTokenCountInMessages(messages);
        } catch (Exception ex) {
            int total = 0;
            for (ChatMessage message : messages) {
                total += sanitizer.messageText(message).length() / 4;
            }
            return Math.max(1, total);
        }
    }

    public String compactRole(ChatMessage message) {
        if (message instanceof UserMessage) {
            return "User";
        }
        if (message instanceof AiMessage) {
            return "Assistant";
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return "Tool " + toolResult.toolName();
        }
        if (message instanceof SystemMessage systemMessage
                && systemMessage.text() != null
                && systemMessage.text().startsWith("SYSTEM TOOL RESULT")) {
            return "Tool/System";
        }
        if (message instanceof SystemMessage) {
            return "System";
        }
        return message.getClass().getSimpleName();
    }

    public String compactText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        int headLength = Math.max(1, (int) (maxChars * 0.65));
        int tailLength = Math.max(1, maxChars - headLength - 35);
        String head = normalized.substring(0, Math.min(headLength, normalized.length()));
        String tail = normalized.substring(Math.max(0, normalized.length() - tailLength));
        int omitted = Math.max(0, normalized.length() - head.length() - tail.length());
        return head + " ... [" + omitted + " chars compacted] ... " + tail;
    }
}
