package com.taskpilot.projects.common.entity;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "comment_mentions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentMentionEntity {

    @EmbeddedId
    private CommentMentionId id;

    @Column(name = "comment_id", insertable = false, updatable = false)
    private Long commentId;

    @Column(name = "user_id", insertable = false, updatable = false)
    private Long userId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommentMentionId implements Serializable {
        @Column(name = "comment_id")
        private Long commentId;

        @Column(name = "user_id")
        private Long userId;
    }
}
