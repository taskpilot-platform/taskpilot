package com.taskpilot.ai.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThinkingNarratorService {

    @Qualifier("groqGatekeeperModel") // Reuse the fast Groq model if available
    private final ObjectProvider<ChatModel> fastModelProvider;
    private ThinkingNarratorAgent agent;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @PostConstruct
    void init() {
        ChatModel fastModel = fastModelProvider.getIfAvailable();
        if (fastModel != null) {
            agent = AiServices.builder(ThinkingNarratorAgent.class)
                    .chatModel(fastModel)
                    .build();
        }
    }

    public String expandSync(String conciseThinking) {
        if (agent == null || conciseThinking == null || conciseThinking.isBlank()) {
            return conciseThinking;
        }
        try {
            return agent.expand(conciseThinking);
        } catch (Exception e) {
            log.warn("[ThinkingNarrator] Failed to expand thinking: {}", e.getMessage());
            return conciseThinking;
        }
    }

    public CompletableFuture<String> expandAsync(String conciseThinking) {
        return CompletableFuture.supplyAsync(() -> expandSync(conciseThinking), executor);
    }
}
