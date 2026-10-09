package com.taskpilot.infrastructure.exception;

import com.taskpilot.infrastructure.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler() {
        this.messageSource = null;
    }

    @Autowired(required = false)
    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException ex) {
        log.debug("Ignore async request not usable (likely client disconnected SSE): {}", ex.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleClientAbortIOException(IOException ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Ignore IO abort caused by client disconnect: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled IOException caught: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        return handleAccessDeniedException(ex, LocaleContextHolder.getLocale());
    }

    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex, Locale locale) {
        String msg = resolveMessage("error.access_denied", null, "Access denied. Admin role required.", locale);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(HttpStatus.FORBIDDEN.value(), msg));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        return handleBusinessException(ex, LocaleContextHolder.getLocale());
    }

    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex, Locale locale) {
        String msg;
        if (ex.getMessageCode() != null) {
            msg = resolveMessage(ex.getMessageCode(), ex.getArgs(), ex.getMessage(), locale);
        } else {
            msg = resolveMessage(ex.getMessage(), ex.getArgs(), ex.getMessage(), locale);
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getStatus(), msg));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        return handleValidationException(ex, LocaleContextHolder.getLocale());
    }

    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex, Locale locale) {
        var error = ex.getBindingResult().getAllErrors().getFirst();
        String resolvedErrorMsg = error.getDefaultMessage();
        if (messageSource != null) {
            try {
                resolvedErrorMsg = messageSource.getMessage(error, locale != null ? locale : LocaleContextHolder.getLocale());
            } catch (Exception ignored) {
                // Keep defaultMessage
            }
        }
        String msg = resolveMessage("error.validation_failed", new Object[]{resolvedErrorMsg},
                "Validation failed: " + resolvedErrorMsg, locale);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), msg));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(NoHandlerFoundException ex) {
        return handleNotFoundException(ex, LocaleContextHolder.getLocale());
    }

    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(NoHandlerFoundException ex, Locale locale) {
        String path = ex.getHttpMethod() + " " + ex.getRequestURL();
        String msg = resolveMessage("error.endpoint_not_found", new Object[]{path},
                "API endpoint not found: " + path, locale);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), msg));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex) {
        return handleMaxUploadSizeExceededException(ex, LocaleContextHolder.getLocale());
    }

    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex, Locale locale) {
        log.warn("Uploaded file exceeds maximum allowed size: {}", ex.getMessage());
        String msg = resolveMessage("error.file_size_exceeded", null,
                "File size exceeds the maximum allowed limit (25MB).", locale);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        return handleGeneralException(ex, LocaleContextHolder.getLocale());
    }

    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex, Locale locale) {
        if (isClientDisconnect(ex)) {
            log.debug("Ignore async response failure caused by client disconnect: {}", ex.getMessage());
            return null;
        }

        log.error("Unhandled Exception caught: ", ex);
        String msg = resolveMessage("error.internal_server", null,
                "Internal server error. Please try again later!", locale);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), msg));
    }

    private String resolveMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        if (messageSource == null || code == null) {
            return defaultMessage;
        }
        try {
            Locale targetLocale = locale != null ? locale : LocaleContextHolder.getLocale();
            return messageSource.getMessage(code, args, defaultMessage, targetLocale);
        } catch (Exception e) {
            return defaultMessage;
        }
    }

    private boolean isClientDisconnect(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String className = current.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(className)
                    || "org.springframework.web.context.request.async.AsyncRequestNotUsableException".equals(className)) {
                return true;
            }

            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("aborted")
                        || normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("client has disconnected")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
