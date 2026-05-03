package com.taskpilot.contracts.assignment.dto;

import java.time.LocalDate;

public record ProjectDueDto(
        Long projectId,
        String name,
        LocalDate endDate,
        String status) {
}
