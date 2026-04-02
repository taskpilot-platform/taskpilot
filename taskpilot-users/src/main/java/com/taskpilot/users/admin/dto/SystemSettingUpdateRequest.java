package com.taskpilot.users.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SystemSettingUpdateRequest(
                @NotBlank(message = "Key name cannot be blank") String keyName,

                @NotNull(message = "Value cannot be null") Object valueJson,

                String description) {
}
