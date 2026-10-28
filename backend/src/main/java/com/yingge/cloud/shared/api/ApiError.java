package com.yingge.cloud.shared.api;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String code,
        String message,
        List<FieldViolation> fieldErrors,
        String traceId,
        Instant timestamp
) {
    public record FieldViolation(String field, String message) {
    }
}

