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

import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class GatekeeperService {

    private static final List<String> FALLBACK_KEYWORDS = List.of(
            "phân công",
            "phan cong",
            "giao việc",
            "giao task",
            "giao viec",
            "chia viec",
            "chia việc",
            "chọn ai",
            "chon ai",
            "chọn người",
            "chon nguoi",
            "tìm người",
            "tim nguoi",
            "ai ranh",
            "ai rảnh",
            "ung vien",
            "ứng viên",
            "assign",
            "candidate",
            "chia task");

    private final ObjectProvider<dev.langchain4j.model.chat.ChatModel> gatekeeperModelProvider;
    private final boolean groqEnabled;

    private GatekeeperAgent gatekeeperAgent;

    public GatekeeperService(
            @Qualifier("groqGatekeeperModel") ObjectProvider<dev.langchain4j.model.chat.ChatModel> gatekeeperModelProvider,
            @Value("${ai.groq.enabled:false}") boolean groqEnabled) {
        this.gatekeeperModelProvider = gatekeeperModelProvider;
        this.groqEnabled = groqEnabled;
    }

    @PostConstruct
    void init() {
        if (!groqEnabled) {
            log.info("[Gatekeeper] Groq is disabled. Gatekeeper model classifier is disabled. Falling back to keyword checks.");
            gatekeeperAgent = null;
            return;
        }
        dev.langchain4j.model.chat.ChatModel gatekeeperModel = gatekeeperModelProvider.getIfAvailable();
        if (gatekeeperModel == null) {
            log.warn("[Gatekeeper] Groq gatekeeper model is not available. Falling back to keyword checks.");
        } else {
            gatekeeperAgent = AiServices.builder(GatekeeperAgent.class)
                    .chatModel(gatekeeperModel)
                    .build();
        }
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
            boolean requiresAHP = intent != null && intent.isRequiresAHP();
            log.info("[Gatekeeper] requiresAHP={} (llama-3.1-8b)", requiresAHP);
            return requiresAHP;
        } catch (Exception ex) {
            log.warn("[Gatekeeper] Gatekeeper model failed, using keyword fallback: {}", ex.getMessage());
            boolean requiresAHP = fallbackRequiresAHP(userMessage);
            log.info("[Gatekeeper] requiresAHP={} (fallback)", requiresAHP);
            return requiresAHP;
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
