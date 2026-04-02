package com.taskpilot.projects.common.entity;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(name = "heuristic_mode")
    private HeuristicMode heuristicMode = HeuristicMode.BALANCED;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public enum ProjectStatus {
        PLANNING, ACTIVE, COMPLETED, ARCHIVED
    }

    public enum HeuristicMode {
        BALANCED, URGENT, TRAINING
    }
}
