package com.taskpilot.infrastructure.exception;

import com.taskpilot.infrastructure.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleMaxUploadSizeExceededException_returnsPayloadTooLarge413() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(25 * 1024 * 1024);

        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSizeExceededException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(413);
        assertThat(response.getBody().getMessage()).contains("25MB");
    }

    @Test
    void handleBusinessException_returnsConfiguredStatusAndMessage() {
        BusinessException ex = new BusinessException(HttpStatus.BAD_REQUEST.value(), "File size exceeds 25MB limit");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).isEqualTo("File size exceeds 25MB limit");
    }
}
