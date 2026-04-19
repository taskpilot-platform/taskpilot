package com.taskpilot.ai.entity;
import com.taskpilot.infrastructure.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
@Entity
@Table(name = "chat_sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSessionEntity extends BaseEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "title")
    private String title;
}

