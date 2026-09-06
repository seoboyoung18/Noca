package com.ssafy.a307.estimatevalidation.dto;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;

import java.time.Instant;

public record ValidationStatusResponse(
        Long validationId,
        EstimateFileType inputType,
        ValidationStatus status,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {
}
