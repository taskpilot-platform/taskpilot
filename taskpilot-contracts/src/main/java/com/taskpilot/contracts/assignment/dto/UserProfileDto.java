package com.taskpilot.contracts.assignment.dto;

public record UserProfileDto(
        Long id,
        String fullName,
        String email,
        String status,
        int currentWorkload,
        boolean activeAccount
) {
    public UserProfileDto(Long id, String fullName, String email, String status, int currentWorkload) {
        this(id, fullName, email, status, currentWorkload, !"DEACTIVATED".equalsIgnoreCase(status));
    }
}
