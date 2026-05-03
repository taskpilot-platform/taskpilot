package com.taskpilot.ai.service;

import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.repository.ChatMessageRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionChatMemoryService {

    private final ChatMemoryStore chatMemoryStore;
    private final TokenCountEstimator tokenCountEstimator;
    private final ChatMessageRepository chatMessageRepository;

    @Value("${ai.chat.history-size:6}")
    private int memoryMaxMessages;

    @Value("${ai.chat.max-user-input-chars:12000}")
    private int maxUserInputChars;

    @Value("${ai.chat.max-user-input-tokens:2500}")
    private int maxUserInputTokens;

    @Value("${ai.chat.max-assistant-memory-chars:1500}")
    private int maxAssistantMemoryChars;

    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("(?s)<think>.*?</think>");

    public List<ChatMessage> appendUserMessage(Long sessionId, String systemPrompt, String userInput) {
        MessageWindowChatMemory memory = memoryForSession(sessionId);
        List<ChatMessage> existing = memory.messages();
        String normalizedInput = normalizeUserInputForMemory(userInput);

        if (existing.isEmpty()) {
            bootstrapFromChatMessages(memory, sessionId, systemPrompt);
            if (!endsWithSameUserMessage(memory.messages(), normalizedInput)) {
                memory.add(UserMessage.from(normalizedInput));
            }
        } else {
            memory.add(UserMessage.from(normalizedInput));
        }

        return memory.messages();
    }

    public void appendAssistantMessage(Long sessionId, String responseText, String systemPrompt) {
        MessageWindowChatMemory memory = memoryForSession(sessionId);
        if (memory.messages().isEmpty()) {
            memory.add(SystemMessage.from(systemPrompt));
        }
        String sanitized = sanitizeAssistantMessage(responseText);
        if (sanitized.isBlank() && responseText != null) {
            sanitized = responseText;
        }
        memory.add(AiMessage.from(sanitized));
    }

    public String sanitizeAssistantMessage(String responseText) {
        if (responseText == null || responseText.isBlank()) {
            return "";
        }
        String sanitized = THINK_TAG_PATTERN.matcher(responseText).replaceAll("").trim();
        if (sanitized.length() <= maxAssistantMemoryChars) {
            return sanitized;
        }

        return sanitized.substring(0, maxAssistantMemoryChars)
                + "\n... [Response truncated for memory]";
    }

    private MessageWindowChatMemory memoryForSession(Long sessionId) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(memoryMaxMessages)
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    private void bootstrapFromChatMessages(MessageWindowChatMemory memory,
            Long sessionId,
            String systemPrompt) {
        memory.add(SystemMessage.from(systemPrompt));

        List<ChatMessageEntity> recentMessages = chatMessageRepository.findLastNBySessionId(
                sessionId,
                PageRequest.of(0, memoryMaxMessages));
        Collections.reverse(recentMessages);

        for (ChatMessageEntity message : recentMessages) {
            if (message.getSender() == SenderType.USER) {
                memory.add(UserMessage.from(message.getContent()));
            } else if (message.getSender() == SenderType.ASSISTANT) {
                String sanitized = sanitizeAssistantMessage(message.getContent());
                if (sanitized.isBlank() && message.getContent() != null) {
                    sanitized = message.getContent();
                }
                memory.add(AiMessage.from(sanitized));
            } else {
                memory.add(SystemMessage.from(message.getContent()));
            }
        }

        log.debug("[Memory] Bootstrapped session {} from {} chat_messages", sessionId,
                recentMessages.size());
    }

    private boolean endsWithSameUserMessage(List<ChatMessage> messages, String userInput) {
        if (messages.isEmpty()) {
            return false;
        }

        ChatMessage last = messages.get(messages.size() - 1);
        if (!(last instanceof UserMessage userMessage)) {
            return false;
        }

        String lastText = userMessage.singleText();
        return lastText != null && lastText.equals(userInput);
    }

    private String normalizeUserInputForMemory(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "";
        }

        int estimatedTokens = tokenCountEstimator.estimateTokenCountInText(userInput);
        if (userInput.length() <= maxUserInputChars && estimatedTokens <= maxUserInputTokens) {
            return userInput;
        }

        int headLength = Math.max(1, maxUserInputChars / 2);
        int tailLength = Math.max(1, maxUserInputChars - headLength);

        String head = userInput.substring(0, Math.min(headLength, userInput.length()));
        String tail = userInput.substring(Math.max(0, userInput.length() - tailLength));
        int omittedChars = Math.max(0, userInput.length() - head.length() - tail.length());

        String normalized = head + "\n\n[... omitted " + omittedChars
                + " chars for context safety ...]\n\n" + tail;

        log.warn("[Memory] User input normalized before memory insert: chars={} -> {}, tokens~{}",
                userInput.length(), normalized.length(), estimatedTokens);
        return normalized;
    }
}
