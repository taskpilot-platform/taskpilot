package com.taskpilot.infrastructure.exception;

import com.taskpilot.infrastructure.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsableException(AsyncRequestNotUsableException ex) {
        log.debug("Ignore async request not usable (likely client disconnected SSE): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Void> handleClientAbortIOException(IOException ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Ignore IO abort caused by client disconnect: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        log.error("Unhandled IOException caught: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(HttpStatus.FORBIDDEN.value(), "Access denied. Admin role required."));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.error(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getAllErrors().getFirst().getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error((HttpStatus.BAD_REQUEST.value()), "Validation failed: " + errorMessage));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFoundException(NoHandlerFoundException ex) {
        String path = ex.getHttpMethod() + " " + ex.getRequestURL();
        String errorMessage = "API endpoint not found: " + path;
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), errorMessage));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        if (isClientDisconnect(ex)) {
            log.debug("Ignore async response failure caused by client disconnect: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }

        log.error("Unhandled Exception caught: ", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal server error. Please try again later!"));
        // hide real internal msg error from client
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
                        || normalized.contains("not usable")
                        || normalized.contains("failed to send")
                        || normalized.contains("servletoutputstream failed to flush")) {
                    return true;
                }
            }

            current = current.getCause();
        }
        return false;
    }
}
