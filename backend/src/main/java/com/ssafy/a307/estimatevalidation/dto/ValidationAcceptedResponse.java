package com.ssafy.a307.estimatevalidation.dto;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;

public record ValidationAcceptedResponse(
        Long validationId,
        ValidationStatus status,
        EstimateFileType inputType,
        String statusUrl) {
}
