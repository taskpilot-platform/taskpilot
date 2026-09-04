package com.taskpilot.ai.streaming.postprocess;

import com.taskpilot.ai.entity.AiChatRequestEntity.Phase;
import com.taskpilot.ai.entity.ChatMessageEntity;
import com.taskpilot.ai.entity.ChatMessageEntity.SenderType;
import com.taskpilot.ai.entity.ChatSessionEntity;
import com.taskpilot.ai.repository.ChatMessageRepository;
import com.taskpilot.ai.repository.ChatSessionRepository;
import com.taskpilot.ai.service.AiLogService;
import com.taskpilot.ai.service.ChatStreamStatusService;
import com.taskpilot.ai.service.SessionChatMemoryService;
import com.taskpilot.ai.streaming.engine.DirectOpenAiModelClient;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles background post-processing tasks: AI session titling and asynchronous database auditing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionPostProcessor {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final AiLogService aiLogService;
    private final SessionChatMemoryService sessionChatMemoryService;
    private final ChatStreamStatusService chatStreamStatusService;
    private final DirectOpenAiModelClient directModelClient;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public void generateSessionTitleViaGemmaAsync(ChatSessionEntity session, String userInput, String toolResults) {
        if (session == null || userInput == null || userInput.isBlank()) {
            return;
        }
        executor.submit(() -> {
            try {
                ChatSessionEntity currentSession = sessionRepository.findById(session.getId()).orElse(session);
                if (currentSession.getTitle() != null && !currentSession.getTitle().isBlank()) {
                    return;
                }

                String titlePrompt = "Hãy đặt một tiêu đề ngắn gọn, tự nhiên cho đoạn hội thoại này bằng tiếng Việt dựa trên câu hỏi của người dùng và kết quả thực thi này. Chỉ trả về duy nhất chuỗi tên hội thoại, không giải thích hay dẫn dắt gì thêm.\n"
                        + "User prompt: " + userInput + "\n"
                        + "System Context: " + toolResults;

                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(UserMessage.from(titlePrompt)))
                        .build();

                log.info("[AutoTitle] Calling gemma-4-31b-it asynchronously to generate title for session {}...", session.getId());
                ChatResponse response = directModelClient.callGemmaDirectly(request, "gemma-4-31b-it", 1, false);

                if (response != null && response.aiMessage() != null && response.aiMessage().text() != null) {
                    String title = response.aiMessage().text();
                    if (title != null) {
                        title = title.replaceAll("(?s)<think>.*?</think>", "");
                        title = title.replaceAll("(?s)<thought>.*?</thought>", "");
                        title = title.replace("<think>", "")
                                .replace("</think>", "")
                                .replace("<thought>", "")
                                .replace("</thought>", "")
                                .replace("\"", "")
                                .replace("'", "")
                                .trim();
                    }
                    if (title != null && title.contains("\n")) {
                        title = title.substring(title.lastIndexOf("\n") + 1).trim();
                    }
                    if (title.length() > 60) {
                        title = title.substring(0, 57) + "...";
                    }
                    if (!title.isEmpty()) {
                        currentSession.setTitle(title);
                        sessionRepository.save(currentSession);
                        session.setTitle(title);
                        log.info("[AutoTitle] Successfully set session {} title to: '{}'", session.getId(), title);
                    }
                }
            } catch (Exception e) {
                log.warn("[AutoTitle] Failed to generate session title via Gemma: {}", e.getMessage());
            }
        });
    }

    public void saveSessionMessagesAndLogsAsync(
            ChatSessionEntity session,
            Long sessionId,
            Long userId,
            String userInput,
            String systemPrompt,
            String responseText,
            String extractedReasoning,
            Collection<String> toolNames,
            Object toolOutput,
            String modelName,
            int estimatedTokens,
            long durationMs,
            String clientMessageId) {
        executor.submit(() -> {
            try {
                ChatMessageEntity assistantMsg = messageRepository.save(ChatMessageEntity.builder()
                        .sessionId(sessionId)
                        .sender(SenderType.ASSISTANT)
                        .content(responseText)
                        .build());

                session.setUpdatedAt(Instant.now());
                if (session.getTitle() == null || session.getTitle().isBlank()) {
                    generateSessionTitleViaGemmaAsync(session, userInput, responseText);
                } else {
                    sessionRepository.save(session);
                }

                String actionTaken = toolNames == null || toolNames.isEmpty() ? null : String.join(",", toolNames);

                aiLogService.saveLog(userId, null, sessionId, assistantMsg.getId(), userInput,
                        responseText, extractedReasoning, actionTaken, toolOutput, modelName,
                        estimatedTokens, (int) durationMs);

                String cleanResponse = sessionChatMemoryService.sanitizeAssistantMessage(responseText);
                sessionChatMemoryService.appendAssistantMessage(sessionId, cleanResponse, systemPrompt);

                chatStreamStatusService.updatePhase(sessionId, clientMessageId,
                        Phase.FINALIZED, modelName, assistantMsg.getId(), null);

                log.info("[SSE] Async DB save and finalization complete for session {} in {}ms", sessionId, durationMs);
            } catch (Exception e) {
                log.error("[SSE] Exception in async finalize for session {}: {}", sessionId, e.getMessage(), e);
            }
        });
    }
}
