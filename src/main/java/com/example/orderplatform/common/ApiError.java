package com.example.orderplatform.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {
    public ApiError(int status, String error, String message) {
        this(Instant.now(), status, error, message, null);
    }

    public ApiError(int status, String error, String message, Map<String, String> fieldErrors) {
        this(Instant.now(), status, error, message, fieldErrors);
    }
}
