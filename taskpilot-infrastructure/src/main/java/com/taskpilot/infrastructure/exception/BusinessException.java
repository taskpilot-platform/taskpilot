package com.taskpilot.infrastructure.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int status;

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }
}
