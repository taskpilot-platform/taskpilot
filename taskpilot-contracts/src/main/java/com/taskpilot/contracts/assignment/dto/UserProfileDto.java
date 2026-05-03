package com.taskpilot.contracts.assignment.dto;

public record UserProfileDto(Long id, String fullName, String email, String status,
        int currentWorkload) {
}
