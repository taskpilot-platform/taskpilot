package com.taskpilot.ai.adapter.fake;

import com.taskpilot.contracts.aiquery.dto.*;

import java.util.List;

public class ScenarioFixtures {

    public static ProjectStatusDto getProjectStatusOnTrack(Long projectId) {
        return new ProjectStatusDto(projectId, "Mocked Project Alpha", "IN_PROGRESS", 100, 45, 2, 45);
    }
    
    public static ProjectStatusDto getProjectStatusAtRisk(Long projectId) {
        return new ProjectStatusDto(projectId, "Mocked Project Beta", "AT_RISK", 50, 10, 15, 20);
    }

    public static List<ProjectMemberDto> getProjectMembers(Long projectId) {
        return List.of(
            new ProjectMemberDto(1L, "Alice Manager", "MANAGER", 0.9, "Agile, Scrum"),
            new ProjectMemberDto(2L, "Bob Developer", "MEMBER", 0.85, "Java, Spring Boot, Kafka"),
            new ProjectMemberDto(3L, "Charlie UI", "MEMBER", 0.7, "React, TypeScript")
        );
    }

    public static List<MemberWorkloadDto> getMemberWorkloads(Long projectId) {
        return List.of(
            new MemberWorkloadDto(2L, "Bob Developer", 5, 1, 8),
            new MemberWorkloadDto(3L, "Charlie UI", 2, 0, 4)
        );
    }

    public static MemberWorkloadDto getSingleMemberWorkload(Long memberId) {
        return new MemberWorkloadDto(memberId, "Bob Developer", 5, 1, 8);
    }

    public static TaskDetailDto getTaskDetails(Long taskId) {
        return new TaskDetailDto(taskId, 1L, "Implement Kafka Producer", "Send events to topic XYZ", "IN_PROGRESS", "HIGH", 8, "[\"Kafka\", \"Java\"]", "2026-06-01", "Bob Developer", 2L);
    }
}
