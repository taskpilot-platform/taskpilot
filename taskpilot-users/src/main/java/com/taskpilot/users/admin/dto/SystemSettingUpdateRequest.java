package com.taskpilot.users.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record SystemSettingUpdateRequest(
        @NotBlank(message = "Key name cannot be blank")
        String keyName,

        @NotBlank(message = "Value cannot be blank")
        String valueJson,

        String description
) {
}
