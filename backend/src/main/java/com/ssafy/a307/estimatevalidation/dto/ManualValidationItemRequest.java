package com.ssafy.a307.estimatevalidation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualValidationItemRequest(
        @Min(1) @Max(32767) int lineNo,
        @NotBlank @Size(max = 200) String rawItemName,
        @NotBlank @Size(max = 20) String workType,
        @Min(1) @Max(32767) long quantity,
        @NotNull @Min(0) Integer partCost,
        @NotNull @Min(0) Integer laborCost) {

    public boolean hasPositiveCost() {
        return partCost != null && laborCost != null && (partCost > 0 || laborCost > 0);
    }
}
