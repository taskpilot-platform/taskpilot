package com.taskpilot.projects.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "project_chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "project_chat_messages_id_seq_gen")
    @SequenceGenerator(name = "project_chat_messages_id_seq_gen", sequenceName = "project_chat_messages_id_seq", allocationSize = 1)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "message_type", nullable = false)
    @Builder.Default
    private String messageType = "TEXT";

    @Column(name = "file_id")
    private Long fileId;

    @Column(name = "created_at", updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
