package com.taskpilot.infrastructure.exception;

import com.taskpilot.infrastructure.config.I18nConfig;
import com.taskpilot.infrastructure.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Locale;
import java.io.IOException;
import java.sql.BatchUpdateException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler defaultHandler = new GlobalExceptionHandler();

    private final MessageSource messageSource = new I18nConfig().messageSource();
    private final GlobalExceptionHandler i18nHandler = new GlobalExceptionHandler(messageSource);

    @Test
    void jdbcBatchAbortedIsNotAnHttpClientDisconnect() {
        var ex = new RuntimeException("Batch entry 0 was aborted",
                new BatchUpdateException("duplicate key", "23505", new int[0]));
        var response = defaultHandler.handleGeneralException(ex);
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void databaseIntegrityConflictReturns409WithoutLeakingSql() {
        var ex = new DataIntegrityViolationException("Batch entry 0 was aborted: secret@example.test",
                new BatchUpdateException("duplicate key", "23505", new int[0]));
        var response = defaultHandler.handleDataIntegrityViolationException(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(409);
        assertThat(response.getBody().getMessage()).doesNotContain("secret@", "Batch", "SQL");
    }

    @Test
    void dataConflictIsLocalizedWithoutLeakingDatabaseDetails() {
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
            var response = i18nHandler.handleDataIntegrityViolationException(new DataIntegrityViolationException("private SQL"));
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getMessage()).isEqualTo("Yêu cầu xung đột với dữ liệu hiện có.");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void runtimeMessageAloneCannotHideAnApplicationError() {
        assertThat(defaultHandler.handleGeneralException(new RuntimeException("connection reset")))
                .isNotNull();
    }

    @Test
    void realIoClientDisconnectStillIgnored() {
        assertThat(defaultHandler.handleClientAbortIOException(new IOException("Broken pipe"))).isNull();
        assertThat(defaultHandler.handleGeneralException(new RuntimeException(new IOException("Connection reset by peer"))))
                .isNull();
    }

    @Test
    void databaseFailureWithSocketCauseIsNotHidden() {
        var sql = new java.sql.SQLException("database transport failed", new IOException("Connection reset"));
        assertThat(defaultHandler.handleGeneralException(new RuntimeException(sql))).isNotNull();
    }

    @Test
    void handleMaxUploadSizeExceededException_returnsPayloadTooLarge413() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(25 * 1024 * 1024);

        ResponseEntity<ApiResponse<Void>> response = defaultHandler.handleMaxUploadSizeExceededException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(413);
        assertThat(response.getBody().getMessage()).contains("25MB");
    }

    @Test
    void handleBusinessException_returnsConfiguredStatusAndMessage() {
        BusinessException ex = new BusinessException(HttpStatus.BAD_REQUEST.value(), "File size exceeds 25MB limit");

        ResponseEntity<ApiResponse<Void>> response = defaultHandler.handleBusinessException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).isEqualTo("File size exceeds 25MB limit");
    }

    @Test
    void handleBusinessException_i18n_resolvesVietnameseMessage() {
        BusinessException ex = new BusinessException(
                HttpStatus.UNAUTHORIZED.value(),
                "error.auth.invalid_credentials",
                "Invalid credentials fallback"
        );

        ResponseEntity<ApiResponse<Void>> response = i18nHandler.handleBusinessException(ex, Locale.forLanguageTag("vi"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Email hoặc mật khẩu không chính xác");
    }

    @Test
    void handleBusinessException_i18n_resolvesEnglishMessage() {
        BusinessException ex = new BusinessException(
                HttpStatus.UNAUTHORIZED.value(),
                "error.auth.invalid_credentials",
                "Invalid credentials fallback"
        );

        ResponseEntity<ApiResponse<Void>> response = i18nHandler.handleBusinessException(ex, Locale.ENGLISH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Invalid email or password");
    }

    @Test
    void handleBusinessException_i18n_resolvesWithArguments() {
        BusinessException ex = new BusinessException(
                HttpStatus.BAD_REQUEST.value(),
                "error.auth.avatar_upload_failed",
                new Object[]{"S3 timeout"},
                "Upload failed"
        );

        ResponseEntity<ApiResponse<Void>> response = i18nHandler.handleBusinessException(ex, Locale.ENGLISH);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Failed to upload avatar: S3 timeout");
    }

    @Test
    void handleAccessDeniedException_i18n_resolvesPerLocale() {
        AccessDeniedException ex = new AccessDeniedException("Forbidden");

        ResponseEntity<ApiResponse<Void>> viResponse = i18nHandler.handleAccessDeniedException(ex, Locale.forLanguageTag("vi"));
        assertThat(viResponse.getBody()).isNotNull();
        assertThat(viResponse.getBody().getMessage()).isEqualTo("Quyền truy cập bị từ chối. Cần quyền Quản trị viên.");

        ResponseEntity<ApiResponse<Void>> enResponse = i18nHandler.handleAccessDeniedException(ex, Locale.ENGLISH);
        assertThat(enResponse.getBody()).isNotNull();
        assertThat(enResponse.getBody().getMessage()).isEqualTo("Access denied. Admin role required.");
    }
}
