package com.taskpilot.ai.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles role alternation, message sanitization, and think-block filtering
 * for multi-turn conversations and tool execution results.
 */
@Slf4j
@Component
public class ChatMessageSanitizer {

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile(
            "<\\s*(?:d?think|thought)\\b[^>]*>(.*?)<\\s*/\\s*(?:d?think|thought)\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ORPHAN_THINK_TAG_PATTERN = Pattern.compile(
            "</?\\s*(?:d?think|thought)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    @Value("${ai.chat.max-tool-result-memory-chars:1200}")
    private int maxToolResultMemoryChars = 1200;

    /**
     * Sanitizes the conversation history before dispatching to tool rounds:
     * 1. Filters out past confirmation messages to avoid memory pollution.
     * 2. Flattens AiMessage tool calls into plain text.
     * 3. Flattens ToolExecutionResultMessage into SystemMessage semantic memory.
     */
    public List<ChatMessage> sanitizeHistoryForTools(List<ChatMessage> rawMessages) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return List.of();
        }

        List<ChatMessage> cleanedRawMessages = new ArrayList<>();
        if (rawMessages.get(0) instanceof SystemMessage) {
            cleanedRawMessages.add(rawMessages.get(0));
        }

        for (int i = (rawMessages.get(0) instanceof SystemMessage ? 1 : 0); i < rawMessages.size(); i++) {
            ChatMessage msg = rawMessages.get(i);
            boolean isLast = (i == rawMessages.size() - 1);

            if (!isLast) {
                if (msg instanceof UserMessage userMsg) {
                    String text = userMsg.singleText();
                    if (text != null) {
                        String lower = text.toLowerCase();
                        if (lower.contains("xac nhan") || lower.contains("xác nhận")
                                || lower.contains("huy bo") || lower.contains("hủy bỏ")
                                || lower.contains("thoi huy") || lower.contains("thôi hủy")) {
                            log.info("[Sanitizer] Skipped past confirmation UserMessage from history: '{}'", text);
                            continue;
                        }
                    }
                } else if (msg instanceof AiMessage aiMsg) {
                    String text = aiMsg.text();
                    if (text != null) {
                        String lower = text.toLowerCase();
                        if (lower.contains("da xac nhan") || lower.contains("đã xác nhận")
                                || lower.contains("da huy") || lower.contains("đã hủy")) {
                            log.info("[Sanitizer] Skipped past confirmation AiMessage from history: '{}'", text);
                            continue;
                        }
                    }
                }
            }
            cleanedRawMessages.add(msg);
        }

        // Keep the first SystemMessage (if any) and at most 8 most recent messages.
        List<ChatMessage> filteredMessages = new ArrayList<>();
        if (!cleanedRawMessages.isEmpty() && cleanedRawMessages.get(0) instanceof SystemMessage) {
            filteredMessages.add(cleanedRawMessages.get(0));
        }

        int startIdx = Math.max(filteredMessages.isEmpty() ? 0 : 1, cleanedRawMessages.size() - 8);
        for (int i = startIdx; i < cleanedRawMessages.size(); i++) {
            if (i == 0 && cleanedRawMessages.get(0) instanceof SystemMessage) {
                continue;
            }
            filteredMessages.add(cleanedRawMessages.get(i));
        }

        List<ChatMessage> safeMessages = new ArrayList<>();

        for (ChatMessage msg : filteredMessages) {
            if (msg instanceof AiMessage aiMsg) {
                String cleanText = aiMsg.text();
                if (cleanText != null) {
                    cleanText = THINK_BLOCK_PATTERN.matcher(cleanText).replaceAll("");
                    cleanText = ORPHAN_THINK_TAG_PATTERN.matcher(cleanText).replaceAll("").trim();
                }

                if (aiMsg.hasToolExecutionRequests()) {
                    String fallbackText = cleanText != null && !cleanText.isBlank()
                            ? cleanText
                            : "[System: AI utilized internal analytical tools]";
                    safeMessages.add(AiMessage.from(fallbackText));
                    log.info("[Sanitizer] Flattened AiMessage tool_calls into plain text.");
                } else {
                    safeMessages.add(AiMessage.from(cleanText != null && !cleanText.isBlank() ? cleanText : "Phản hồi trống."));
                }
            } else if (msg instanceof ToolExecutionResultMessage toolResult) {
                String toolName = toolResult.toolName();
                String rawData = truncate(toolResult.text(), maxToolResultMemoryChars);

                String memoryInjection = String.format("SYSTEM TOOL RESULT [%s]:\n%s\n\nCRITICAL INSTRUCTION: You MUST base your final recommendation entirely on this data.", toolName, rawData);
                safeMessages.add(SystemMessage.from(memoryInjection));

                log.info("[Sanitizer] Injected flattened Tool Result '{}' as Semantic Memory.", toolName);
            } else {
                safeMessages.add(msg);
            }
        }

        return safeMessages;
    }

    /**
     * Alternates roles strictly: User -> AI -> User -> AI to adhere to Gemini & OpenAI requirements.
     */
    public List<ChatMessage> cleanAndAlternateRoles(List<ChatMessage> messages, boolean isGemini) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ChatMessage> cleaned = new ArrayList<>();
        int startIndex = 0;
        if (messages.get(0) instanceof SystemMessage sysMsg) {
            cleaned.add(sysMsg);
            startIndex = 1;
        }

        List<ChatMessage> body = new ArrayList<>();
        for (int i = startIndex; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage sys) {
                body.add(UserMessage.from(sys.text()));
            } else {
                body.add(msg);
            }
        }

        if (body.isEmpty()) {
            return cleaned;
        }

        List<ChatMessage> alternated = new ArrayList<>();
        ChatMessage current = body.get(0);
        for (int i = 1; i < body.size(); i++) {
            ChatMessage next = body.get(i);
            if (sameRole(current, next)) {
                current = mergeMessages(current, next);
            } else {
                alternated.add(current);
                current = next;
            }
        }
        alternated.add(current);

        if (!alternated.isEmpty() && alternated.get(0) instanceof AiMessage) {
            List<ChatMessage> temp = new ArrayList<>();
            temp.add(UserMessage.from("[System: Continued conversation]"));
            temp.addAll(alternated);
            alternated = temp;
        }

        cleaned.addAll(alternated);
        return cleaned;
    }

    public boolean sameRole(ChatMessage m1, ChatMessage m2) {
        if (m1 instanceof UserMessage && m2 instanceof UserMessage) {
            return true;
        }
        if (m1 instanceof AiMessage && m2 instanceof AiMessage) {
            return true;
        }
        if (m1 instanceof SystemMessage && m2 instanceof SystemMessage) {
            return true;
        }
        return false;
    }

    public ChatMessage mergeMessages(ChatMessage m1, ChatMessage m2) {
        String combinedText = messageText(m1) + "\n\n" + messageText(m2);
        if (m1 instanceof UserMessage) {
            return UserMessage.from(combinedText);
        }
        if (m1 instanceof AiMessage) {
            return AiMessage.from(combinedText);
        }
        return SystemMessage.from(combinedText);
    }

    public String extractAllThinkBlocks(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }

        Matcher matcher = THINK_BLOCK_PATTERN.matcher(rawResponse);
        List<String> blocks = new ArrayList<>();
        while (matcher.find()) {
            String block = matcher.group(1);
            if (block != null && !block.isBlank()) {
                blocks.add(block.trim());
            }
        }

        if (blocks.isEmpty()) {
            return null;
        }
        return String.join("\n\n", blocks);
    }

    public String stripThinkBlocks(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return "";
        }

        String withoutCompleteBlocks = THINK_BLOCK_PATTERN.matcher(rawResponse).replaceAll(" ");
        String withoutOrphanTags = ORPHAN_THINK_TAG_PATTERN.matcher(withoutCompleteBlocks).replaceAll(" ");
        return withoutOrphanTags.trim();
    }

    public String stripThinkTags(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<(?:think|thought)>[\\s\\S]*?</(?:think|thought)>", "").trim();
    }

    public String stripToolCallJson(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Pattern p = Pattern.compile("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```");
        Matcher m = p.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String jsonContent = m.group(1);
            if (jsonContent.contains("\"tool\"") || jsonContent.contains("\"chains\"") || jsonContent.contains("\"steps\"")) {
                m.appendReplacement(sb, "");
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        String result = sb.toString().trim();

        if (result.startsWith("{") && result.contains("\"tool\"") && result.contains("\"arguments\"")) {
            int lastBrace = result.lastIndexOf("}");
            if (lastBrace != -1) {
                result = result.substring(lastBrace + 1).trim();
            }
        }
        return result;
    }

    public String messageText(ChatMessage message) {
        if (message == null) {
            return "";
        }
        if (message instanceof UserMessage userMessage) {
            return userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return stripThinkTags(aiMessage.text());
        }
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return toolResult.text();
        }
        return message.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "... [truncated]";
    }
}
