package com.ssafy.a307.estimatevalidation.dto;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ManualValidationRequest(
        @NotNull @Positive Long accidentId,
        @Positive Long estimateId,
        @NotNull EstimateFileType fileType,
        @Positive Integer claimedTotal,
        @NotEmpty @Size(max = 200) List<@Valid ManualValidationItemRequest> items) {

    @AssertTrue(message = "fileType은 MANUAL이어야 합니다.")
    public boolean isManualType() {
        return fileType == EstimateFileType.MANUAL;
    }

    @AssertTrue(message = "각 항목의 부품비와 공임 중 하나는 0보다 커야 합니다.")
    public boolean hasPositiveCostOnEveryItem() {
        return items == null || items.stream().allMatch(ManualValidationItemRequest::hasPositiveCost);
    }

    @AssertTrue(message = "lineNo는 견적서 안에서 중복될 수 없습니다.")
    public boolean hasDistinctLineNumbers() {
        return items == null || items.stream().map(ManualValidationItemRequest::lineNo).distinct().count() == items.size();
    }
}
