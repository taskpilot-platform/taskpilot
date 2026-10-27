package com.taskpilot.users.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuth2ExchangeRequest(
        @NotBlank(message = "Exchange code is required") String code) {
}
