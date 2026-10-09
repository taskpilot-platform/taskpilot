package com.taskpilot.infrastructure.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int status;
    private final String messageCode;
    private final Object[] args;

    public BusinessException(int status, String message) {
        this(status, null, null, message);
    }

    public BusinessException(int status, String messageCode, String defaultMessage) {
        this(status, messageCode, null, defaultMessage);
    }

    public BusinessException(int status, String messageCode, Object[] args, String defaultMessage) {
        super(defaultMessage);
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Invalid HTTP status code for BusinessException: " + status);
        }
        this.status = status;
        this.messageCode = messageCode;
        this.args = args;
    }
}
