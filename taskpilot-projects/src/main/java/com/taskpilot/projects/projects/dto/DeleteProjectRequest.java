package com.taskpilot.projects.projects.dto;

import java.util.UUID;

public record DeleteProjectRequest(
        String confirmationText,
        UUID requestId
) {
}
