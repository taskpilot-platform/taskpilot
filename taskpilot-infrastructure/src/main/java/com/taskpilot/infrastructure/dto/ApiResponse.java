package com.taskpilot.infrastructure.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ApiResponse<T> {
    private int status;
    private String message;
    private T data;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    public static <T> ApiResponse<T> success(T data){
        return ApiResponse.<T>builder()
                .status(200)
                .message("Success")
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(int status, String message) {
        return ApiResponse.<T>builder()
                .status(status)
                .message(message)
                .data(null)
                .build();
    }
}

