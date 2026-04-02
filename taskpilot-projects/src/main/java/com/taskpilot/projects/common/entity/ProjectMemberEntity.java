package com.taskpilot.projects.common.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "project_members")
@IdClass(ProjectMemberId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectMemberEntity {

    @Id
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false, insertable = false, updatable = false)
    private ProjectEntity project;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private MemberRole role = MemberRole.MEMBER;

    @Column(name = "joined_at", insertable = false, updatable = false)
    private Instant joinedAt;

    @Builder.Default
    @Column(name = "performance_score")
    private Double performanceScore = 0.5;

    public enum MemberRole {
        MANAGER, MEMBER
    }
}
