package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;

import java.time.Instant;

public interface EstimateValidationStateView {
    Long getValidationId();
    EstimateFileType getFileType();
    ValidationStatus getStatus();
    String getFailureReason();
    Instant getCreatedAt();
    Instant getCompletedAt();
}
