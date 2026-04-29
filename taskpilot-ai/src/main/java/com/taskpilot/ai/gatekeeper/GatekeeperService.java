package com.taskpilot.ai.gatekeeper;

import dev.langchain4j.service.AiServices;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatekeeperService {

    private static final List<String> FALLBACK_KEYWORDS = List.of(
            "phan cong",
            "giao task",
            "giao viec",
            "chia viec",
            "tim nguoi",
            "ai ranh",
            "ung vien",
            "assign",
            "candidate",
            "workload",
            "chia task");

    @Qualifier("groqGatekeeperModel")
    private final ObjectProvider<dev.langchain4j.model.chat.ChatModel> gatekeeperModelProvider;

    private GatekeeperAgent gatekeeperAgent;

    @PostConstruct
    void init() {
        dev.langchain4j.model.chat.ChatModel gatekeeperModel = gatekeeperModelProvider.getIfAvailable();
        if (gatekeeperModel == null) {
            log.warn("[Gatekeeper] Groq gatekeeper model is not available. Falling back to keyword checks.");
            return;
        }
        gatekeeperAgent = AiServices.builder(GatekeeperAgent.class)
                .chatModel(gatekeeperModel)
                .build();
    }

    public boolean requiresAHP(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }

        if (gatekeeperAgent == null) {
            return fallbackRequiresAHP(userMessage);
        }

        try {
            IntentResult intent = gatekeeperAgent.classify(userMessage);
            return intent != null && intent.isRequiresAHP();
        } catch (Exception ex) {
            log.warn("[Gatekeeper] Gatekeeper model failed, using keyword fallback: {}", ex.getMessage());
            return fallbackRequiresAHP(userMessage);
        }
    }

    private boolean fallbackRequiresAHP(String userMessage) {
        String normalized = userMessage.toLowerCase(Locale.ROOT);
        for (String keyword : FALLBACK_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
