package com.taskpilot.infrastructure.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int status;

    public BusinessException(int status, String message) {
        super(message);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Invalid HTTP status code for BusinessException: " + status);
        }
        this.status = status;
    }
}
