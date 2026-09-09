package com.taskpilot.users.common.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "skills")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "skills_id_seq_gen")
    @SequenceGenerator(name = "skills_id_seq_gen", sequenceName = "skills_id_seq", allocationSize = 1)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    private String description;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;
}
