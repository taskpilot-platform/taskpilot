package com.taskpilot.ai.memory;

import com.taskpilot.ai.entity.AiChatMemoryEntity;
import com.taskpilot.ai.repository.AiChatMemoryRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JpaChatMemoryStore implements ChatMemoryStore {

    private final AiChatMemoryRepository aiChatMemoryRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(Object memoryId) {
        Long sessionId = toSessionId(memoryId);
        return aiChatMemoryRepository.findById(sessionId)
                .map(AiChatMemoryEntity::getMessagesJson)
                .filter(json -> !json.isBlank())
                .map(ChatMessageDeserializer::messagesFromJson)
                .orElseGet(List::of);
    }

    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Long sessionId = toSessionId(memoryId);
        String payload = ChatMessageSerializer.messagesToJson(messages);

        AiChatMemoryEntity entity = aiChatMemoryRepository.findById(sessionId)
                .orElseGet(() -> AiChatMemoryEntity.builder().sessionId(sessionId).build());
        entity.setMessagesJson(payload);
        aiChatMemoryRepository.save(entity);
    }

    @Override
    @Transactional
    public void deleteMessages(Object memoryId) {
        aiChatMemoryRepository.deleteById(toSessionId(memoryId));
    }

    private Long toSessionId(Object memoryId) {
        if (memoryId instanceof Number number) {
            return number.longValue();
        }
        if (memoryId instanceof String text) {
            return Long.parseLong(text);
        }
        throw new IllegalArgumentException("Unsupported memoryId type: "
                + (memoryId == null ? "null" : memoryId.getClass().getName()));
    }
}
