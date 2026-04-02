package com.taskpilot.projects.common.entity;

import java.io.Serializable;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberId implements Serializable {

    private Long projectId;
    private Long userId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProjectMemberId that)) {
            return false;
        }
        return Objects.equals(projectId, that.projectId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectId, userId);
    }
}
