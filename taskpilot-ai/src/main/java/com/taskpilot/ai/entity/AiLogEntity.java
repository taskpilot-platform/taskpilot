package com.taskpilot.ai.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.Instant;

@Entity
@Table(name = "ai_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class AiLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "user_id")
    private Long userId;
    @Column(name = "project_id")
    private Long projectId;
    @Column(name = "session_id")
    private Long sessionId;
    @Column(name = "chat_message_id")
    private Long chatMessageId;
    @Column(name = "request", columnDefinition = "TEXT")
    private String request;
    @Column(name = "response", columnDefinition = "TEXT")
    private String response;
    @Column(name = "reasoning", columnDefinition = "TEXT")
    private String reasoning;
    @Column(name = "action_taken")
    private String actionTaken;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_output", columnDefinition = "jsonb")
    private Object toolOutput;
    @Builder.Default
    @Column(name = "human_feedback")
    private String humanFeedback = "PENDING";
    @Column(name = "model_used")
    private String modelUsed;
    @Column(name = "tokens_used")
    private Integer tokensUsed;
    @Column(name = "duration_ms")
    private Integer durationMs;
}

