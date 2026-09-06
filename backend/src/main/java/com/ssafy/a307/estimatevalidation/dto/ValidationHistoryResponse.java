package com.ssafy.a307.estimatevalidation.dto;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;

import java.time.Instant;
import java.util.List;

public record ValidationHistoryResponse(
        List<Entry> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public record Entry(
            Long validationId,
            Long accidentId,
            String manufacturer,
            String modelName,
            Short modelYear,
            EstimateFileType inputType,
            ValidationStatus status,
            ValidationGrade grade,
            Integer claimedTotal,
            int reviewItemCount,
            int totalItemCount,
            Instant createdAt,
            Instant completedAt) {
    }
}
