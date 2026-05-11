package com.taskpilot.projects.common.entity;

import java.io.Serializable;

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
@Table(name = "task_labels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskLabelEntity {

    @EmbeddedId
    private TaskLabelId id;

    @Column(name = "task_id", insertable = false, updatable = false)
    private Long taskId;

    @Column(name = "label_id", insertable = false, updatable = false)
    private Long labelId;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskLabelId implements Serializable {
        @Column(name = "task_id")
        private Long taskId;

        @Column(name = "label_id")
        private Long labelId;
    }
}
