package com.taskpilot.ai.repository;

import com.taskpilot.ai.entity.AiChatMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AiChatMemoryRepository extends JpaRepository<AiChatMemoryEntity, Long> {
}
