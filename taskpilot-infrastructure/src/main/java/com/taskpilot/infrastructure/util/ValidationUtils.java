package com.taskpilot.infrastructure.util;

import com.taskpilot.infrastructure.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

public final class ValidationUtils {

    private ValidationUtils() {
    }

    public static void validateDateRange(LocalDate startDate, LocalDate endDate, String errorMessage) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), errorMessage);
        }
    }
}
