package com.taskpilot.ai.memory;

import com.taskpilot.ai.util.TokenEstimationUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskPilotTokenCountEstimator implements TokenCountEstimator {

    private final TokenEstimationUtil tokenEstimationUtil;

    @Override
    public int estimateTokenCountInText(String text) {
        return tokenEstimationUtil.estimate(text);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        if (message instanceof UserMessage userMessage) {
            return tokenEstimationUtil.estimate(userMessage.singleText());
        }
        if (message instanceof AiMessage aiMessage) {
            return tokenEstimationUtil.estimate(aiMessage.text());
        }
        if (message instanceof SystemMessage systemMessage) {
            return tokenEstimationUtil.estimate(systemMessage.text());
        }
        return tokenEstimationUtil.estimate(message.toString());
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int total = 0;
        if (messages == null) {
            return total;
        }
        for (ChatMessage message : messages) {
            total += estimateTokenCountInMessage(message);
        }
        return total;
    }
}
