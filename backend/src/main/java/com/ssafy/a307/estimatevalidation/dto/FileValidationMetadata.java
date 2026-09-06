package com.ssafy.a307.estimatevalidation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FileValidationMetadata(
        @NotNull @Positive Long accidentId,
        @Positive Long estimateId) {
}
